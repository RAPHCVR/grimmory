package org.booklore.service.downloads.client;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class QbittorrentClient {

    private static final Pattern BTIH_PATTERN = Pattern.compile("(?i)(?:urn:btih:|btih%3A)([a-z0-9]{32,40})");
    private static final int DEFAULT_TIMEOUT_MINUTES = 180;
    private static final int DEFAULT_POLL_SECONDS = 10;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;

    public QbittorrentConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "qbittorrent");
        String baseUrl = firstNonBlank(node.path("baseUrl").asText(null), configReader.firstText(source, "qbittorrentBaseUrl", null));
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new DownloadSourceException("Torrent downloads require source config qbittorrent.baseUrl");
        }
        String username = node.path("username").asText(null);
        String password = node.path("password").asText(null);
        String category = node.path("category").asText("booklore");
        String tags = node.path("tags").asText("booklore");
        String remoteSavePath = node.path("remoteSavePath").asText(null);
        String localSavePath = node.path("localSavePath").asText(null);
        int pollIntervalSeconds = Math.max(2, node.path("pollIntervalSeconds").asInt(DEFAULT_POLL_SECONDS));
        int timeoutMinutes = Math.max(1, node.path("timeoutMinutes").asInt(DEFAULT_TIMEOUT_MINUTES));
        boolean deleteTorrentOnComplete = node.path("deleteTorrentOnComplete").asBoolean(true);
        boolean deleteFilesOnComplete = node.path("deleteFilesOnComplete").asBoolean(true);
        return new QbittorrentConfig(
                trimTrailingSlash(baseUrl),
                username,
                password,
                category,
                tags,
                remoteSavePath,
                localSavePath,
                pollIntervalSeconds,
                timeoutMinutes,
                deleteTorrentOnComplete,
                deleteFilesOnComplete
        );
    }

    public SubmittedTorrent addUrl(QbittorrentConfig config, String url, String remoteSavePath, String jobTag) {
        String cookie = login(config);
        List<MultipartField> fields = new ArrayList<>();
        fields.add(new MultipartField("urls", url));
        fields.add(new MultipartField("savepath", remoteSavePath));
        if (config.category() != null && !config.category().isBlank()) {
            fields.add(new MultipartField("category", config.category()));
        }
        String tags = mergeTags(config.tags(), jobTag);
        if (!tags.isBlank()) {
            fields.add(new MultipartField("tags", tags));
        }

        try {
            String boundary = "BookLoreBoundary" + UUID.randomUUID().toString().replace("-", "");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v2/torrents/add"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Referer", config.baseUrl())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(multipartBody(boundary, fields), StandardCharsets.UTF_8));
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("qBittorrent add torrent failed with HTTP status " + response.statusCode() + ": " + response.body());
            }
            return new SubmittedTorrent(extractBtih(url).orElse(null), jobTag);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("qBittorrent add torrent interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("qBittorrent add torrent failed: " + e.getMessage(), e);
        }
    }

    public Optional<TorrentInfo> findTorrent(QbittorrentConfig config, String hash, String jobTag) {
        String cookie = login(config);
        String query = hash != null && !hash.isBlank()
                ? "?hashes=" + encode(hash.toLowerCase(Locale.ROOT))
                : "?tag=" + encode(jobTag);
        if ((hash == null || hash.isBlank()) && config.category() != null && !config.category().isBlank()) {
            query += "&category=" + encode(config.category());
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v2/torrents/info" + query))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Referer", config.baseUrl())
                    .GET();
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("qBittorrent torrent info failed with HTTP status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray()) {
                return Optional.empty();
            }
            for (JsonNode item : root) {
                TorrentInfo info = toInfo(item);
                if (hash != null && !hash.isBlank() && hash.equalsIgnoreCase(info.hash())) {
                    return Optional.of(info);
                }
                if (hash == null || hash.isBlank()) {
                    return Optional.of(info);
                }
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("qBittorrent torrent info interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("qBittorrent torrent info failed: " + e.getMessage(), e);
        }
    }

    public void deleteTorrent(QbittorrentConfig config, String hash, boolean deleteFiles) {
        if (hash == null || hash.isBlank()) {
            return;
        }
        String cookie = login(config);
        String body = "hashes=" + encode(hash.toLowerCase(Locale.ROOT)) + "&deleteFiles=" + deleteFiles;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v2/torrents/delete"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Referer", config.baseUrl())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Deletion is best-effort. The staged copy is already under BookLore control.
        }
    }

    private String login(QbittorrentConfig config) {
        if (config.username() == null || config.username().isBlank()) {
            return null;
        }
        try {
            String body = "username=" + encode(config.username()) + "&password=" + encode(config.password() == null ? "" : config.password());
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v2/auth/login"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Referer", config.baseUrl())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299 || !"Ok.".equals(response.body() == null ? "" : response.body().trim())) {
                throw new DownloadSourceException("qBittorrent login failed with HTTP status " + response.statusCode());
            }
            return response.headers().firstValue("set-cookie")
                    .map(value -> value.split(";", 2)[0])
                    .orElseThrow(() -> new DownloadSourceException("qBittorrent login did not return SID cookie"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("qBittorrent login interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("qBittorrent login failed: " + e.getMessage(), e);
        }
    }

    private TorrentInfo toInfo(JsonNode item) {
        return new TorrentInfo(
                item.path("hash").asText(null),
                item.path("name").asText(null),
                item.path("state").asText(null),
                item.path("progress").asDouble(0D),
                item.path("content_path").asText(null),
                item.path("save_path").asText(null)
        );
    }

    private Optional<String> extractBtih(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = BTIH_PATTERN.matcher(value);
        return matcher.find() ? Optional.of(matcher.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    private String multipartBody(String boundary, List<MultipartField> fields) {
        StringBuilder body = new StringBuilder();
        for (MultipartField field : fields) {
            body.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(field.name()).append("\"\r\n\r\n")
                    .append(field.value()).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        return body.toString();
    }

    private String mergeTags(String configuredTags, String jobTag) {
        String base = configuredTags == null ? "" : configuredTags.trim();
        if (base.isBlank()) {
            return jobTag;
        }
        return base + "," + jobTag;
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

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record MultipartField(String name, String value) {
    }

    public record SubmittedTorrent(String hash, String jobTag) {
    }

    public record TorrentInfo(String hash, String name, String state, double progress, String contentPath, String savePath) {
        public boolean complete() {
            return progress >= 0.999D;
        }
    }

    public record QbittorrentConfig(String baseUrl,
                                    String username,
                                    String password,
                                    String category,
                                    String tags,
                                    String remoteSavePath,
                                    String localSavePath,
                                    int pollIntervalSeconds,
                                    int timeoutMinutes,
                                    boolean deleteTorrentOnComplete,
                                    boolean deleteFilesOnComplete) {
        public String resolveRemoteSavePath(Path stagingDir, Long jobId) {
            return resolvePath(remoteSavePath, stagingDir, jobId);
        }

        public Path resolveLocalSavePath(Path stagingDir, Long jobId) {
            String path = resolvePath(localSavePath, stagingDir, jobId);
            return Path.of(path);
        }

        private String resolvePath(String template, Path stagingDir, Long jobId) {
            if (template == null || template.isBlank()) {
                return stagingDir.toAbsolutePath().toString();
            }
            return template
                    .replace("{stagingDir}", stagingDir.toAbsolutePath().toString())
                    .replace("{jobId}", String.valueOf(jobId));
        }
    }
}
