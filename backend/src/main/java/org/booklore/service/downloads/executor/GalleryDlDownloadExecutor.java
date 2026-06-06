package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

@Component
@RequiredArgsConstructor
public class GalleryDlDownloadExecutor implements DownloadExecutor {

    private static final String DEFAULT_BINARY = "gallery-dl";
    private static final long DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int LOG_TAIL_LINES = 80;
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,3})(?:\\.\\d+)?\\s*%");

    private final DownloadSourceConfigReader configReader;

    @Override
    public boolean supports(DownloadAcquisitionType acquisitionType) {
        return acquisitionType == DownloadAcquisitionType.CLI_GALLERY_DL;
    }

    @Override
    public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
        GalleryDlConfig config = readConfig(request.getSource());
        String url = firstNonBlank(request.getResult().getDownloadUrl(), request.getResult().getDetailsUrl());
        if (url == null) {
            throw new DownloadSourceException("gallery-dl result does not expose a URL");
        }
        if (isUnboundedWebtoonsSeriesUrl(url)) {
            throw new DownloadSourceException("gallery-dl Webtoons series/list URL is not a bounded acquisition target; select a concrete episode/viewer URL or include an episode number");
        }

        Instant startedAt = Instant.now();
        List<String> command = new ArrayList<>();
        command.add(config.binaryPath());
        command.addAll(config.extraArgs());
        command.add("--cbz");
        command.add("--destination");
        command.add(request.getStagingDir().toAbsolutePath().toString());
        command.add(url);

        ExecutorService logExecutor = Executors.newFixedThreadPool(2);
        try {
            Files.createDirectories(request.getStagingDir());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(request.getStagingDir().toFile());
            builder.environment().putAll(config.environment());

            if (progressSink != null) {
                progressSink.onProgress(1);
            }
            Process process = builder.start();
            AtomicInteger bestProgress = new AtomicInteger(1);
            CompletableFuture<ProcessLog> stdout = captureLog(process.getInputStream(), progressSink, bestProgress, logExecutor);
            CompletableFuture<ProcessLog> stderr = captureLog(process.getErrorStream(), progressSink, bestProgress, logExecutor);

            boolean completed = process.waitFor(config.timeoutMinutes(), TimeUnit.MINUTES);
            if (!completed) {
                destroyProcessTree(process);
                throw new DownloadSourceException("gallery-dl timed out after " + config.timeoutMinutes() + " minutes");
            }

            ProcessLog stdoutLog = awaitLog(stdout);
            ProcessLog stderrLog = awaitLog(stderr);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new DownloadSourceException("gallery-dl failed with exit code " + exitCode + logTail(stdoutLog, stderrLog));
            }

            Path cbz = findGeneratedCbz(request.getStagingDir(), startedAt)
                    .orElseThrow(() -> new DownloadSourceException("gallery-dl completed but no CBZ was generated" + logTail(stdoutLog, stderrLog)));
            verifyCbz(cbz);
            if (progressSink != null) {
                progressSink.onProgress(100);
            }
            return cbz;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("gallery-dl download interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (IOException e) {
            throw new DownloadSourceException("gallery-dl execution failed: " + e.getMessage(), e);
        } finally {
            logExecutor.shutdownNow();
        }
    }

    private CompletableFuture<ProcessLog> captureLog(InputStream stream,
                                                     DownloadProgressSink progressSink,
                                                     AtomicInteger bestProgress,
                                                     ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
            ArrayDeque<String> tail = new ArrayDeque<>(LOG_TAIL_LINES);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendTail(tail, line);
                    reportProgress(line, progressSink, bestProgress);
                }
            } catch (IOException e) {
                appendTail(tail, "[log read failed] " + e.getMessage());
            }
            return new ProcessLog(List.copyOf(tail));
        }, executor);
    }

    private ProcessLog awaitLog(CompletableFuture<ProcessLog> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ProcessLog(List.of("[log read incomplete] " + e.getMessage()));
        }
    }

    private void reportProgress(String line, DownloadProgressSink progressSink, AtomicInteger bestProgress) {
        if (progressSink == null || line == null) {
            return;
        }
        Matcher matcher = PERCENT_PATTERN.matcher(line);
        while (matcher.find()) {
            int parsed = Math.min(99, Math.max(1, Integer.parseInt(matcher.group(1))));
            bestProgress.updateAndGet(current -> {
                if (parsed > current) {
                    progressSink.onProgress(parsed);
                    return parsed;
                }
                return current;
            });
        }
    }

    private Optional<Path> findGeneratedCbz(Path stagingDir, Instant startedAt) {
        Instant lowerBound = startedAt.minusSeconds(5);
        try (Stream<Path> paths = Files.walk(stagingDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cbz"))
                    .filter(path -> isModifiedAfter(path, lowerBound))
                    .max(Comparator
                            .comparing(this::lastModifiedOrEpoch)
                            .thenComparingLong(this::sizeOrZero));
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to inspect gallery-dl staging directory: " + e.getMessage(), e);
        }
    }

    private boolean isModifiedAfter(Path path, Instant lowerBound) {
        return !lastModifiedOrEpoch(path).isBefore(lowerBound);
    }

    private Instant lastModifiedOrEpoch(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private void verifyCbz(Path cbz) {
        try {
            if (Files.size(cbz) <= 0) {
                throw new DownloadSourceException("gallery-dl generated an empty CBZ: " + cbz.getFileName());
            }
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(cbz))) {
                if (zip.getNextEntry() == null) {
                    throw new DownloadSourceException("gallery-dl generated an invalid CBZ: " + cbz.getFileName());
                }
            }
        } catch (DownloadSourceException e) {
            throw e;
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to validate gallery-dl CBZ: " + e.getMessage(), e);
        }
    }

    private void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private GalleryDlConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "galleryDl");
        String binaryPath = firstNonBlank(
                node.path("binaryPath").asText(null),
                node.path("binary").asText(null),
                configReader.firstText(source, "galleryDlBinaryPath", DEFAULT_BINARY)
        );
        long timeoutMinutes = clampLong(node.path("timeoutMinutes").asLong(DEFAULT_TIMEOUT_MINUTES), 1, 720);
        return new GalleryDlConfig(binaryPath, timeoutMinutes, stringList(node.path("extraArgs")), stringMap(node.path("environment")));
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }

    private Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String value = entry.getValue().asText(null);
            if (value != null) {
                values.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(values);
    }

    private String logTail(ProcessLog stdout, ProcessLog stderr) {
        List<String> lines = new ArrayList<>();
        if (stdout != null && !stdout.tail().isEmpty()) {
            lines.add("stdout:");
            lines.addAll(stdout.tail());
        }
        if (stderr != null && !stderr.tail().isEmpty()) {
            lines.add("stderr:");
            lines.addAll(stderr.tail());
        }
        if (lines.isEmpty()) {
            return "";
        }
        return System.lineSeparator() + String.join(System.lineSeparator(), lines);
    }

    private void appendTail(ArrayDeque<String> tail, String line) {
        if (tail.size() == LOG_TAIL_LINES) {
            tail.removeFirst();
        }
        tail.addLast(line);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isUnboundedWebtoonsSeriesUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            String query = uri.getRawQuery();
            if (host == null || path == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean webtoons = normalizedHost.equals("webtoons.com") || normalizedHost.endsWith(".webtoons.com");
            if (!webtoons) {
                return false;
            }
            String normalizedPath = path.toLowerCase(Locale.ROOT);
            String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
            return normalizedPath.endsWith("/list")
                    && normalizedQuery.contains("title_no=")
                    && !normalizedQuery.contains("episode_no=");
        } catch (Exception ignored) {
            return false;
        }
    }

    private long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private record GalleryDlConfig(String binaryPath, long timeoutMinutes, List<String> extraArgs, Map<String, String> environment) {
    }

    private record ProcessLog(List<String> tail) {
    }
}
