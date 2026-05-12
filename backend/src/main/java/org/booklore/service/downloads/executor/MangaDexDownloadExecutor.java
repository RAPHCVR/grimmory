package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
@RequiredArgsConstructor
public class MangaDexDownloadExecutor implements DownloadExecutor {

    private static final String DEFAULT_API_BASE_URL = "https://api.mangadex.org";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;

    @Override
    public boolean supports(DownloadAcquisitionType acquisitionType) {
        return acquisitionType == DownloadAcquisitionType.MANGADEX_CHAPTER;
    }

    @Override
    public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
        MangaDexDownloadConfig config = readConfig(request.getSource());
        String chapterId = chapterId(request.getResult().getDownloadUrl());
        AtHomeChapter chapter = atHomeChapter(config, chapterId);
        if (chapter.files().isEmpty()) {
            throw new DownloadSourceException("MangaDex chapter has no downloadable pages: " + chapterId);
        }

        try {
            Files.createDirectories(request.getTargetPartFile().getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(request.getTargetPartFile()))) {
                for (int i = 0; i < chapter.files().size(); i++) {
                    String fileName = chapter.files().get(i);
                    byte[] page = downloadPage(chapter.pageUrl(fileName, config.dataSaver()), config.timeoutSeconds());
                    ZipEntry entry = new ZipEntry(String.format(Locale.ROOT, "%03d%s", i + 1, extension(fileName)));
                    zip.putNextEntry(entry);
                    try (ByteArrayInputStream in = new ByteArrayInputStream(page)) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                    if (progressSink != null) {
                        progressSink.onProgress((int) Math.min(99, ((i + 1L) * 100L) / chapter.files().size()));
                    }
                }
            }
            if (progressSink != null) {
                progressSink.onProgress(100);
            }
            return request.getTargetPartFile();
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to write MangaDex CBZ: " + e.getMessage(), e);
        }
    }

    private AtHomeChapter atHomeChapter(MangaDexDownloadConfig config, String chapterId) {
        URI uri = URI.create(config.apiBaseUrl() + "/at-home/server/" + chapterId);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("MangaDex at-home request failed with HTTP status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode chapter = root.path("chapter");
            String baseUrl = root.path("baseUrl").asText(null);
            String hash = chapter.path("hash").asText(null);
            JsonNode data = config.dataSaver() ? chapter.path("dataSaver") : chapter.path("data");
            if (baseUrl == null || baseUrl.isBlank() || hash == null || hash.isBlank() || !data.isArray()) {
                throw new DownloadSourceException("MangaDex at-home response is missing baseUrl/hash/pages");
            }
            List<String> files = new ArrayList<>();
            for (JsonNode file : data) {
                String value = file.asText(null);
                if (value != null && !value.isBlank()) {
                    files.add(value);
                }
            }
            return new AtHomeChapter(trimTrailingSlash(baseUrl), hash, files);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("MangaDex at-home request interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("MangaDex at-home request failed: " + e.getMessage(), e);
        }
    }

    private byte[] downloadPage(String url, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("MangaDex page download failed with HTTP status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("MangaDex page download interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("MangaDex page download failed: " + e.getMessage(), e);
        }
    }

    private MangaDexDownloadConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "mangadex");
        String apiBaseUrl = firstNonBlank(node.path("apiBaseUrl").asText(null), configReader.firstText(source, "mangadexApiBaseUrl", DEFAULT_API_BASE_URL));
        int timeoutSeconds = Math.max(3, node.path("timeoutSeconds").asInt(30));
        boolean dataSaver = node.path("dataSaver").asBoolean(false);
        return new MangaDexDownloadConfig(trimTrailingSlash(apiBaseUrl), timeoutSeconds, dataSaver);
    }

    private String chapterId(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new DownloadSourceException("MangaDex result does not expose a chapter id");
        }
        String value = downloadUrl.trim();
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ".jpg";
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record MangaDexDownloadConfig(String apiBaseUrl, int timeoutSeconds, boolean dataSaver) {
    }

    private record AtHomeChapter(String baseUrl, String hash, List<String> files) {
        String pageUrl(String fileName, boolean dataSaver) {
            return baseUrl + "/" + (dataSaver ? "data-saver" : "data") + "/" + hash + "/" + fileName;
        }
    }
}
