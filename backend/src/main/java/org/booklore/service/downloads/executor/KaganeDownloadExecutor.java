package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.client.FlareSolverrClient;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
@RequiredArgsConstructor
public class KaganeDownloadExecutor implements DownloadExecutor {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_USER_AGENT = "BookLore-Downloads";
    private static final String DEFAULT_IMAGE_SELECTOR = ".reading-content img, .page-break img, .entry-content img, .chapter-content img, .reader-area img, .wp-manga-chapter-img, article img, main img";
    private static final List<String> IMAGE_URL_ATTRIBUTES = List.of(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-url",
            "data-cfsrc",
            "src"
    );

    private final FlareSolverrClient flareSolverrClient;
    private final HttpClient httpClient;
    private final DownloadSourceConfigReader configReader;

    @Override
    public boolean supports(DownloadAcquisitionType acquisitionType) {
        return acquisitionType == DownloadAcquisitionType.KAGANE_CHAPTER;
    }

    @Override
    public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
        String chapterUrl = request.getResult().getDownloadUrl();
        validateHttpUrl(chapterUrl, "Kagane chapter URL");
        KaganeConfig config = readConfig(request);
        Path pagesDir = request.getStagingDir().resolve("kagane-pages");

        try {
            Files.createDirectories(pagesDir);
            createParentDirectories(request.getTargetPartFile());

            if (progressSink != null) {
                progressSink.onProgress(5);
            }
            FlareSolverrClient.ResolvedPage solvedPage = flareSolverrClient.fetchPage(request.getSource(), chapterUrl);
            if (progressSink != null) {
                progressSink.onProgress(10);
            }

            List<String> imageUrls = extractImageUrls(solvedPage.response(), chapterUrl, config.imageSelector());
            if (imageUrls.isEmpty()) {
                throw pageStateException(solvedPage.response());
            }

            List<PageFile> pages = downloadImages(chapterUrl, solvedPage, imageUrls, pagesDir, config.timeoutSeconds(), progressSink);
            writeCbz(request.getTargetPartFile(), pages, progressSink);
            deleteDirectory(pagesDir);
            if (progressSink != null) {
                progressSink.onProgress(100);
            }
            return request.getTargetPartFile();
        } catch (DownloadSourceException e) {
            deletePartialFile(request.getTargetPartFile());
            throw e;
        } catch (Exception e) {
            deletePartialFile(request.getTargetPartFile());
            throw new DownloadSourceException("Failed to acquire Kagane chapter: " + e.getMessage(), e);
        }
    }

    private List<String> extractImageUrls(String html, String chapterUrl, String selector) {
        if (html == null || html.isBlank()) {
            throw new DownloadSourceException("FlareSolverr returned empty Kagane HTML");
        }
        Document document = Jsoup.parse(html, chapterUrl);
        Set<String> urls = new LinkedHashSet<>();
        for (Element image : document.select(selector)) {
            for (String attribute : IMAGE_URL_ATTRIBUTES) {
                addImageUrl(urls, resolveImageUrl(image, attribute, chapterUrl));
            }
            addImageUrl(urls, resolveSrcSetUrl(image.attr("data-srcset"), chapterUrl));
            addImageUrl(urls, resolveSrcSetUrl(image.attr("srcset"), chapterUrl));
        }
        return List.copyOf(urls);
    }

    private DownloadSourceException pageStateException(String html) {
        Document document = Jsoup.parse(html == null ? "" : html);
        String title = document.title();
        String normalizedTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String normalizedBody = document.text().toLowerCase(Locale.ROOT);
        if (normalizedTitle.contains("site under maintenance") || normalizedBody.contains("site under maintenance")) {
            return new DownloadSourceException("Kagane is currently serving a maintenance page instead of a reader chapter");
        }
        if (normalizedTitle.contains("404") || normalizedBody.contains("404 page not found")) {
            return new DownloadSourceException("Kagane chapter URL returned a 404 page instead of readable chapter images");
        }
        if (normalizedBody.contains("sign in") || normalizedBody.contains("welcome back") || normalizedBody.contains("login")) {
            return new DownloadSourceException("Kagane chapter URL requires authentication and did not expose readable chapter images");
        }
        return new DownloadSourceException("Kagane chapter page did not contain readable image URLs");
    }

    private void addImageUrl(Set<String> urls, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        if (isHttpUrl(candidate)) {
            urls.add(candidate);
        }
    }

    private String resolveImageUrl(Element image, String attribute, String chapterUrl) {
        String raw = image.attr(attribute);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String absolute = image.absUrl(attribute);
        return firstNonBlank(absolute, resolveAgainst(raw, chapterUrl));
    }

    private String resolveSrcSetUrl(String rawSrcSet, String chapterUrl) {
        if (rawSrcSet == null || rawSrcSet.isBlank()) {
            return null;
        }
        String selected = null;
        for (String candidate : rawSrcSet.split(",")) {
            String value = candidate.trim();
            if (value.isBlank()) {
                continue;
            }
            selected = value.split("\\s+", 2)[0];
        }
        return resolveAgainst(selected, chapterUrl);
    }

    private String resolveAgainst(String raw, String chapterUrl) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            URI baseUri = URI.create(chapterUrl);
            if (trimmed.startsWith("//")) {
                String scheme = firstNonBlank(baseUri.getScheme(), "https");
                return scheme + ":" + trimmed;
            }
            return baseUri.resolve(trimmed).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private List<PageFile> downloadImages(String chapterUrl,
                                          FlareSolverrClient.ResolvedPage solvedPage,
                                          List<String> imageUrls,
                                          Path pagesDir,
                                          int timeoutSeconds,
                                          DownloadProgressSink progressSink) {
        List<PageFile> pages = new ArrayList<>(imageUrls.size());
        for (int i = 0; i < imageUrls.size(); i++) {
            String imageUrl = imageUrls.get(i);
            DownloadedImage image = downloadImage(chapterUrl, imageUrl, solvedPage, timeoutSeconds);
            Path pageFile = pagesDir.resolve(String.format(Locale.ROOT, "%03d%s", i + 1, image.extension()));
            try {
                Files.write(pageFile, image.bytes());
            } catch (IOException e) {
                throw new DownloadSourceException("Failed to write Kagane image " + pageFile.getFileName() + ": " + e.getMessage(), e);
            }
            pages.add(new PageFile(i, pageFile));
            if (progressSink != null) {
                progressSink.onProgress(Math.min(89, 10 + (((i + 1) * 80) / imageUrls.size())));
            }
        }
        return pages;
    }

    private DownloadedImage downloadImage(String chapterUrl,
                                          String imageUrl,
                                          FlareSolverrClient.ResolvedPage solvedPage,
                                          int timeoutSeconds) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("User-Agent", firstNonBlank(solvedPage.userAgent(), DEFAULT_USER_AGENT))
                    .header("Referer", chapterUrl)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET();

            String cookies = solvedPage.cookieHeader();
            if (!cookies.isBlank()) {
                builder.header("Cookie", cookies);
            }

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("Kagane image download failed with HTTP status " + response.statusCode() + " for " + imageUrl);
            }
            if (response.body() == null || response.body().length == 0) {
                throw new DownloadSourceException("Kagane image download returned an empty body for " + imageUrl);
            }

            Optional<String> contentType = response.headers().firstValue("Content-Type");
            if (!isAcceptedImageContentType(contentType)) {
                throw new DownloadSourceException("Kagane image download returned unsupported content type " + contentType.orElse("<missing>") + " for " + imageUrl);
            }
            return new DownloadedImage(extension(imageUrl, contentType), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Kagane image download interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("Kagane image download failed for " + imageUrl + ": " + e.getMessage(), e);
        }
    }

    private void writeCbz(Path targetPartFile, List<PageFile> pages, DownloadProgressSink progressSink) {
        List<PageFile> orderedPages = pages.stream()
                .sorted(Comparator.comparingInt(PageFile::index))
                .toList();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(targetPartFile))) {
            for (int i = 0; i < orderedPages.size(); i++) {
                PageFile page = orderedPages.get(i);
                ZipEntry entry = new ZipEntry(page.path().getFileName().toString());
                zip.putNextEntry(entry);
                Files.copy(page.path(), zip);
                zip.closeEntry();
                if (progressSink != null) {
                    progressSink.onProgress(Math.min(99, 90 + (((i + 1) * 10) / orderedPages.size())));
                }
            }
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to write Kagane CBZ: " + e.getMessage(), e);
        }
    }

    private KaganeConfig readConfig(DownloadExecutionRequest request) {
        JsonNode node = configReader.firstSection(request.getSource(), "kagane");
        String imageSelector = firstNonBlank(
                node.path("imageSelector").asText(null),
                configReader.firstText(request.getSource(), "kaganeImageSelector", null),
                DEFAULT_IMAGE_SELECTOR
        );
        int timeoutSeconds = clamp(node.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS), 3, 120);
        return new KaganeConfig(imageSelector, timeoutSeconds);
    }

    private void validateHttpUrl(String url, String label) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new DownloadSourceException(label + " must be an HTTP(S) URL");
            }
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException(label + " is invalid: " + url, e);
        }
    }

    private boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
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
            case "image/svg+xml" -> ".svg";
            default -> null;
        };
    }

    private void createParentDirectories(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void deletePartialFile(Path targetPartFile) {
        try {
            Files.deleteIfExists(targetPartFile);
        } catch (IOException ignored) {
        }
    }

    private void deleteDirectory(Path directory) {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record KaganeConfig(String imageSelector, int timeoutSeconds) {
    }

    private record DownloadedImage(String extension, byte[] bytes) {
    }

    private record PageFile(int index, Path path) {
    }
}
