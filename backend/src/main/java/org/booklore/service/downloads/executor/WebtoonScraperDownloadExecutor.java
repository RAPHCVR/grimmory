package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import org.booklore.model.enums.DownloadAcquisitionType;
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
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
@RequiredArgsConstructor
public class WebtoonScraperDownloadExecutor implements DownloadExecutor {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_PARALLELISM = 4;
    private static final int MAX_PARALLELISM = 12;
    private static final String DEFAULT_USER_AGENT = "BookLore-Downloads";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(DownloadAcquisitionType acquisitionType) {
        return acquisitionType == DownloadAcquisitionType.IMAGE_SEQUENCE_CBZ;
    }

    @Override
    public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
        ImageSequenceManifest manifest = readManifest(request.getResult().getRawJson());
        AtomicInteger completedDownloads = new AtomicInteger();

        try {
            Files.createDirectories(request.getTargetPartFile().getParent());
            List<PageImage> pages = downloadPages(manifest, completedDownloads, progressSink);
            writeCbz(request.getTargetPartFile(), pages, progressSink);
            if (progressSink != null) {
                progressSink.onProgress(100);
            }
            return request.getTargetPartFile();
        } catch (DownloadSourceException e) {
            deletePartialFile(request.getTargetPartFile());
            throw e;
        } catch (Exception e) {
            deletePartialFile(request.getTargetPartFile());
            throw new DownloadSourceException("Failed to generate image-sequence CBZ: " + e.getMessage(), e);
        }
    }

    private List<PageImage> downloadPages(ImageSequenceManifest manifest,
                                          AtomicInteger completedDownloads,
                                          DownloadProgressSink progressSink) {
        ExecutorService executor = Executors.newFixedThreadPool(manifest.parallelism());
        try {
            List<Future<PageImage>> futures = new ArrayList<>();
            for (int i = 0; i < manifest.imageUrls().size(); i++) {
                int pageIndex = i;
                String imageUrl = manifest.imageUrls().get(i);
                futures.add(executor.submit(() -> {
                    PageImage page = downloadPage(pageIndex, imageUrl, manifest);
                    int completed = completedDownloads.incrementAndGet();
                    if (progressSink != null) {
                        progressSink.onProgress(Math.min(89, (completed * 90) / manifest.imageUrls().size()));
                    }
                    return page;
                }));
            }

            List<PageImage> pages = new ArrayList<>(manifest.imageUrls().size());
            for (Future<PageImage> future : futures) {
                pages.add(future.get());
            }
            pages.sort((left, right) -> Integer.compare(left.index(), right.index()));
            return pages;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Image-sequence download interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DownloadSourceException downloadSourceException) {
                throw downloadSourceException;
            }
            throw new DownloadSourceException("Image-sequence page download failed: " + cause.getMessage(), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private PageImage downloadPage(int index, String url, ImageSequenceManifest manifest) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(manifest.timeoutSeconds()))
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("User-Agent", manifest.userAgent())
                    .GET();
            if (manifest.referer() != null) {
                builder.header("Referer", manifest.referer());
            }

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("Image page download failed with HTTP status " + response.statusCode() + " for " + url);
            }
            if (response.body() == null || response.body().length == 0) {
                throw new DownloadSourceException("Image page download returned an empty body for " + url);
            }

            Optional<String> contentType = response.headers().firstValue("Content-Type");
            if (!isAcceptedImageContentType(contentType)) {
                throw new DownloadSourceException("Image page download returned unsupported content type " + contentType.orElse("<missing>") + " for " + url);
            }
            return new PageImage(index, extension(url, contentType), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Image page download interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("Image page download failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private void writeCbz(Path targetPartFile, List<PageImage> pages, DownloadProgressSink progressSink) {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(targetPartFile))) {
            for (int i = 0; i < pages.size(); i++) {
                PageImage page = pages.get(i);
                ZipEntry entry = new ZipEntry(String.format(Locale.ROOT, "%03d%s", i + 1, page.extension()));
                zip.putNextEntry(entry);
                try (ByteArrayInputStream in = new ByteArrayInputStream(page.bytes())) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
                if (progressSink != null) {
                    progressSink.onProgress(Math.min(99, 90 + (((i + 1) * 10) / pages.size())));
                }
            }
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to write image-sequence CBZ: " + e.getMessage(), e);
        }
    }

    private ImageSequenceManifest readManifest(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new DownloadSourceException("Image-sequence result is missing rawJson manifest");
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode imageUrlsNode = root.path("imageUrls");
            if (!imageUrlsNode.isArray()) {
                throw new DownloadSourceException("Image-sequence manifest must contain an imageUrls array");
            }

            List<String> imageUrls = new ArrayList<>();
            for (JsonNode imageUrlNode : imageUrlsNode) {
                String imageUrl = imageUrlNode.asText(null);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                URI uri = URI.create(imageUrl.trim());
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    throw new DownloadSourceException("Image-sequence manifest contains a non-HTTP image URL");
                }
                imageUrls.add(imageUrl.trim());
            }
            if (imageUrls.isEmpty()) {
                throw new DownloadSourceException("Image-sequence manifest contains no image URLs");
            }

            String referer = blankToNull(root.path("referer").asText(null));
            String userAgent = firstNonBlank(root.path("userAgent").asText(null), DEFAULT_USER_AGENT);
            int timeoutSeconds = clamp(root.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS), 3, 120);
            int parallelism = clamp(root.path("parallelism").asInt(DEFAULT_PARALLELISM), 1, MAX_PARALLELISM);
            return new ImageSequenceManifest(imageUrls, referer, userAgent, timeoutSeconds, parallelism);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("Invalid image-sequence manifest: " + e.getMessage(), e);
        }
    }

    private boolean isAcceptedImageContentType(Optional<String> contentType) {
        if (contentType.isEmpty()) {
            return true;
        }
        String normalized = contentType.get().toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return normalized.startsWith("image/")
                || "application/octet-stream".equals(normalized)
                || "binary/octet-stream".equals(normalized);
    }

    private String extension(String url, Optional<String> contentType) {
        String fromContentType = extensionFromContentType(contentType.orElse(null));
        if (fromContentType != null) {
            return fromContentType;
        }
        try {
            String path = URI.create(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{2,5}")) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
            return ".jpg";
        }
        return ".jpg";
    }

    private String extensionFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/avif" -> ".avif";
            case "image/bmp" -> ".bmp";
            case "image/tiff" -> ".tiff";
            default -> null;
        };
    }

    private void deletePartialFile(Path targetPartFile) {
        try {
            Files.deleteIfExists(targetPartFile);
        } catch (IOException ignored) {
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ImageSequenceManifest(List<String> imageUrls, String referer, String userAgent, int timeoutSeconds, int parallelism) {
    }

    private record PageImage(int index, String extension, byte[] bytes) {
    }
}
