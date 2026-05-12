package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class StacksDownloadExecutor implements DownloadExecutor {

    private static final String EXTERNAL_TASK_TYPE = "STACKS";
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_POLL_SECONDS = 10;
    private static final int DEFAULT_TIMEOUT_MINUTES = 180;
    private static final Set<String> DEFAULT_COMPLETED_STATUSES = Set.of("completed", "complete", "finished", "done", "success", "succeeded", "downloaded");
    private static final Set<String> DEFAULT_FAILED_STATUSES = Set.of("failed", "error", "cancelled", "canceled", "aborted");
    private static final List<String> TASK_ID_FIELDS = List.of("taskId", "task_id", "downloadId", "download_id", "id", "uuid", "jobId", "job_id");
    private static final List<String> STATUS_URL_FIELDS = List.of("statusUrl", "status_url", "links.status", "status.href");
    private static final List<String> STATUS_FIELDS = List.of("status", "state", "task.status", "download.status");
    private static final List<String> PROGRESS_FIELDS = List.of("progressPercent", "progress_percent", "percent", "percentage", "progress", "download.progress");
    private static final List<String> FILE_PATH_FIELDS = List.of("filePath", "file_path", "outputPath", "output_path", "localPath", "local_path", "path", "downloadedFile", "downloaded_file", "result.path", "file.path");
    private static final List<String> ERROR_FIELDS = List.of("error", "errorMessage", "error_message", "message", "reason");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;
    private final DownloadJobRepository jobRepository;

    @Override
    public boolean supports(DownloadAcquisitionType acquisitionType) {
        return acquisitionType == DownloadAcquisitionType.EXTERNAL_STACKS;
    }

    @Override
    public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
        StacksConfig config = readConfig(request);
        String externalIdentifier = externalIdentifier(request.getResult());
        if (externalIdentifier == null) {
            throw new DownloadSourceException("Stacks result requires an id, md5, download URL, or details URL");
        }

        if (progressSink != null) {
            progressSink.onProgress(1);
        }

        SubmittedStacksTask submitted = submitTask(config, request, externalIdentifier);
        saveExternalTask(request.getJob(), firstNonBlank(submitted.taskId(), submitted.statusUrl()));

        StacksStatus initialStatus = toStatus(submitted.responseBody());
        if (isComplete(initialStatus, config)) {
            Path completed = resolveCompletedFile(initialStatus, config, request);
            if (progressSink != null) {
                progressSink.onProgress(100);
            }
            return completed;
        }
        if (isFailed(initialStatus, config)) {
            throw new DownloadSourceException("Stacks download failed: " + firstNonBlank(initialStatus.errorMessage(), initialStatus.status(), "unknown error"));
        }

        URI statusUri = statusUri(config, submitted);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(config.timeoutMinutes()));
        StacksStatus lastStatus = initialStatus;
        int lastProgress = 1;
        while (Instant.now().isBefore(deadline)) {
            lastStatus = fetchStatus(config, statusUri);
            Integer progress = lastStatus.progressPercent();
            if (progress != null && progressSink != null) {
                lastProgress = Math.max(lastProgress, Math.min(99, Math.max(1, progress)));
                progressSink.onProgress(lastProgress);
            }
            if (isComplete(lastStatus, config)) {
                Path completed = resolveCompletedFile(lastStatus, config, request);
                if (progressSink != null) {
                    progressSink.onProgress(100);
                }
                return completed;
            }
            if (isFailed(lastStatus, config)) {
                throw new DownloadSourceException("Stacks download failed: " + firstNonBlank(lastStatus.errorMessage(), lastStatus.status(), "unknown error"));
            }
            sleep(config.pollIntervalSeconds());
        }

        String state = lastStatus == null ? "no status response" : firstNonBlank(lastStatus.status(), "unknown state");
        throw new DownloadSourceException("Stacks download did not complete before timeout: " + state);
    }

    private SubmittedStacksTask submitTask(StacksConfig config, DownloadExecutionRequest request, String externalIdentifier) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("id", externalIdentifier);
            payload.put("md5", externalIdentifier);
            payload.put("jobId", String.valueOf(request.getJob().getId()));
            payload.put("title", request.getResult().getTitle());
            payload.put("format", request.getResult().getFormat().extension());
            payload.put("contentKind", request.getResult().getContentKind().name());
            payload.put("stagingPath", config.resolveRemoteStagingPath(request.getStagingDir(), request.getJob().getId()));
            payload.put("outputDir", config.resolveRemoteStagingPath(request.getStagingDir(), request.getJob().getId()));
            putIfPresent(payload, "downloadUrl", request.getResult().getDownloadUrl());
            putIfPresent(payload, "detailsUrl", request.getResult().getDetailsUrl());
            putIfPresent(payload, "isbn", request.getResult().getIsbn());
            putIfPresent(payload, "language", request.getResult().getLanguage());
            if (request.getResult().getAuthors() != null && !request.getResult().getAuthors().isEmpty()) {
                payload.put("authors", String.join(", ", request.getResult().getAuthors()));
            }
            if (request.getResult().getRawJson() != null && !request.getResult().getRawJson().isBlank()) {
                payload.put("rawJson", request.getResult().getRawJson());
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder(config.submitUri())
                    .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
            addAuthenticationHeaders(builder, config);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("Stacks submit failed with HTTP status " + response.statusCode() + ": " + response.body());
            }

            JsonNode body = parseJson(response.body());
            String taskId = firstText(body, TASK_ID_FIELDS);
            String statusUrl = firstText(body, STATUS_URL_FIELDS);
            if (taskId == null && statusUrl == null && !isComplete(toStatus(body), config)) {
                throw new DownloadSourceException("Stacks submit response did not expose a task id or status URL");
            }
            return new SubmittedStacksTask(taskId, statusUrl, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Stacks submit interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("Stacks submit failed: " + e.getMessage(), e);
        }
    }

    private StacksStatus fetchStatus(StacksConfig config, URI statusUri) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(statusUri)
                    .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .GET();
            addAuthenticationHeaders(builder, config);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("Stacks status failed with HTTP status " + response.statusCode() + ": " + response.body());
            }
            return toStatus(parseJson(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Stacks status polling interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("Stacks status polling failed: " + e.getMessage(), e);
        }
    }

    private URI statusUri(StacksConfig config, SubmittedStacksTask submitted) {
        String statusUrl = submitted.statusUrl();
        if (statusUrl != null && !statusUrl.isBlank()) {
            return config.resolveUrl(statusUrl);
        }
        if (submitted.taskId() == null || submitted.taskId().isBlank()) {
            throw new DownloadSourceException("Stacks did not return a task id to poll");
        }
        return config.resolveUrl(config.statusUrlTemplate().replace("{taskId}", submitted.taskId()).replace("{id}", submitted.taskId()));
    }

    private StacksStatus toStatus(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new StacksStatus(null, null, null, null, root);
        }
        return new StacksStatus(
                firstText(root, STATUS_FIELDS),
                firstProgress(root),
                firstText(root, FILE_PATH_FIELDS),
                firstText(root, ERROR_FIELDS),
                root
        );
    }

    private boolean isComplete(StacksStatus status, StacksConfig config) {
        JsonNode root = status.raw();
        if (root != null && (root.path("complete").asBoolean(false) || root.path("completed").asBoolean(false) || root.path("done").asBoolean(false))) {
            return true;
        }
        String normalized = normalizeStatus(status.status());
        return normalized != null && config.completedStatuses().contains(normalized);
    }

    private boolean isFailed(StacksStatus status, StacksConfig config) {
        JsonNode root = status.raw();
        if (root != null && (root.path("failed").asBoolean(false) || root.path("error").isObject())) {
            return true;
        }
        String normalized = normalizeStatus(status.status());
        return normalized != null && config.failedStatuses().contains(normalized);
    }

    private Path resolveCompletedFile(StacksStatus status, StacksConfig config, DownloadExecutionRequest request) {
        Path stagingDir = request.getStagingDir().toAbsolutePath().normalize();
        String returnedPath = status.filePath();
        if (returnedPath != null && !returnedPath.isBlank()) {
            Path candidate = localCandidateFor(returnedPath, config, request);
            if (!isSameOrChild(candidate, stagingDir)) {
                throw new DownloadSourceException("Stacks completed outside the BookLore staging directory: " + candidate);
            }
            if (Files.exists(candidate)) {
                return waitForStableFile(candidate);
            }
        }

        return findCompletedPayload(stagingDir)
                .map(this::waitForStableFile)
                .orElseThrow(() -> new DownloadSourceException("Stacks completed but no supported file was visible in staging: " + stagingDir));
    }

    private Optional<Path> findCompletedPayload(Path stagingDir) {
        try (Stream<Path> paths = Files.walk(stagingDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> BookFileExtension.fromFileName(path.getFileName().toString()).isPresent())
                    .filter(path -> !isPartialFile(path))
                    .max(Comparator
                            .comparing(this::lastModifiedOrEpoch)
                            .thenComparingLong(this::sizeOrZero));
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to inspect Stacks staging directory: " + e.getMessage(), e);
        }
    }

    private Path waitForStableFile(Path file) {
        try {
            long previousSize = -1L;
            for (int i = 0; i < 20; i++) {
                if (!Files.exists(file)) {
                    throw new DownloadSourceException("Stacks completed file disappeared from staging: " + file);
                }
                long currentSize = Files.size(file);
                if (currentSize > 0 && currentSize == previousSize) {
                    return file;
                }
                previousSize = currentSize;
                Thread.sleep(250);
            }
            throw new DownloadSourceException("Stacks completed file did not stabilize: " + file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Stacks completed file stability check interrupted", e);
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to inspect Stacks completed file: " + e.getMessage(), e);
        }
    }

    private boolean isPartialFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".part") || name.endsWith(".tmp") || name.endsWith(".crdownload");
    }

    private Path localCandidateFor(String returnedPath, StacksConfig config, DownloadExecutionRequest request) {
        String remoteRoot = config.resolveRemoteStagingPath(request.getStagingDir(), request.getJob().getId());
        String remoteRootNormalized = normalizePathText(remoteRoot);
        String returnedNormalized = normalizePathText(returnedPath);
        if (remoteRootNormalized != null && returnedNormalized.startsWith(remoteRootNormalized)) {
            String relative = returnedNormalized.substring(remoteRootNormalized.length());
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return request.getStagingDir().resolve(relative).toAbsolutePath().normalize();
        }
        if (returnedPath.startsWith("file:")) {
            return Path.of(URI.create(returnedPath)).toAbsolutePath().normalize();
        }
        Path candidate = Path.of(returnedPath);
        if (!candidate.isAbsolute()) {
            candidate = request.getStagingDir().resolve(candidate);
        }
        return candidate.toAbsolutePath().normalize();
    }

    private String externalIdentifier(NormalizedDownloadResult result) {
        String rawIdentifier = null;
        try {
            if (result.getRawJson() != null && !result.getRawJson().isBlank()) {
                rawIdentifier = firstText(objectMapper.readTree(result.getRawJson()), List.of("md5", "id", "hash", "externalId", "external_id", "guid"));
            }
        } catch (Exception ignored) {
            // Raw JSON is advisory; sourceResultId/downloadUrl remain usable.
        }
        return firstNonBlank(result.getSourceResultId(), rawIdentifier, result.getDownloadUrl(), result.getDetailsUrl());
    }

    private void saveExternalTask(DownloadJobEntity job, String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return;
        }
        job.setExternalTaskId(externalId);
        job.setExternalTaskType(EXTERNAL_TASK_TYPE);
        jobRepository.save(job);
    }

    private void addAuthenticationHeaders(HttpRequest.Builder request, StacksConfig config) {
        if (config.apiKey() != null && config.authorizationScheme() != null) {
            request.header("Authorization", config.authorizationScheme() + " " + config.apiKey());
        } else if (config.apiKey() != null && config.apiKeyHeader() != null) {
            request.header(config.apiKeyHeader(), config.apiKey());
        }
        if (config.username() != null && !config.username().isBlank()) {
            String token = config.username() + ":" + (config.password() == null ? "" : config.password());
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private StacksConfig readConfig(DownloadExecutionRequest request) {
        JsonNode node = configReader.firstSection(request.getSource(), "stacks");
        String baseUrl = firstNonBlank(node.path("baseUrl").asText(null), configReader.firstText(request.getSource(), "stacksBaseUrl", null));
        String apiUrl = firstNonBlank(
                node.path("apiUrl").asText(null),
                node.path("downloadUrl").asText(null),
                node.path("downloadEndpointUrl").asText(null),
                configReader.firstText(request.getSource(), "stacksApiUrl", null)
        );
        String downloadEndpoint = blankToNull(node.path("downloadEndpoint").asText("/api/download"));
        URI submitUri = resolveSubmitUri(baseUrl, apiUrl, downloadEndpoint);
        String statusUrlTemplate = firstNonBlank(
                node.path("statusUrlTemplate").asText(null),
                node.path("statusEndpointUrl").asText(null),
                submitUri.toString() + "/{taskId}"
        );

        String apiKey = firstNonBlank(node.path("apiKey").asText(null), configReader.firstText(request.getSource(), "stacksApiKey", null));
        String apiKeyHeader = blankToNull(node.path("apiKeyHeader").asText(null));
        String authorizationScheme = blankToNull(node.path("authorizationScheme").asText(apiKeyHeader == null ? "Bearer" : null));
        String username = blankToNull(node.path("username").asText(null));
        String password = blankToNull(node.path("password").asText(null));
        int requestTimeoutSeconds = Math.max(3, node.path("requestTimeoutSeconds").asInt(DEFAULT_REQUEST_TIMEOUT_SECONDS));
        int pollIntervalSeconds = Math.max(1, node.path("pollIntervalSeconds").asInt(DEFAULT_POLL_SECONDS));
        int timeoutMinutes = Math.max(1, node.path("timeoutMinutes").asInt(DEFAULT_TIMEOUT_MINUTES));
        String remoteStagingPath = firstNonBlank(
                node.path("remoteStagingPath").asText(null),
                node.path("stagingPath").asText(null),
                node.path("outputDir").asText(null),
                "{stagingDir}"
        );

        return new StacksConfig(
                submitUri,
                statusUrlTemplate,
                apiKey,
                apiKeyHeader,
                authorizationScheme,
                username,
                password,
                requestTimeoutSeconds,
                pollIntervalSeconds,
                timeoutMinutes,
                remoteStagingPath,
                statusList(node.path("completedStatuses"), DEFAULT_COMPLETED_STATUSES),
                statusList(node.path("failedStatuses"), DEFAULT_FAILED_STATUSES)
        );
    }

    private URI resolveSubmitUri(String baseUrl, String apiUrl, String downloadEndpoint) {
        if (apiUrl != null && !apiUrl.isBlank()) {
            if (apiUrl.startsWith("http://") || apiUrl.startsWith("https://")) {
                return URI.create(apiUrl);
            }
            if (baseUrl != null && !baseUrl.isBlank()) {
                return URI.create(trimTrailingSlash(baseUrl) + "/" + trimLeadingSlash(apiUrl));
            }
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new DownloadSourceException("Stacks downloads require stacks.apiUrl or stacks.baseUrl in source config");
        }
        return URI.create(trimTrailingSlash(baseUrl) + "/" + trimLeadingSlash(downloadEndpoint == null ? "/api/download" : downloadEndpoint));
    }

    private JsonNode parseJson(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(body);
    }

    private String firstText(JsonNode node, List<String> fields) {
        for (String field : fields) {
            JsonNode value = path(node, field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private Integer firstProgress(JsonNode root) {
        for (String field : PROGRESS_FIELDS) {
            JsonNode value = path(root, field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                double number = value.asDouble(0D);
                return clampProgress(number <= 1D && number > 0D ? number * 100D : number);
            }
            String text = value.asText(null);
            if (text != null && !text.isBlank()) {
                try {
                    return clampProgress(Double.parseDouble(text.replace("%", "").trim()));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Integer clampProgress(double value) {
        return (int) Math.max(0, Math.min(100, Math.round(value)));
    }

    private JsonNode path(JsonNode node, String dottedPath) {
        JsonNode current = node;
        for (String segment : dottedPath.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return current;
            }
        }
        return current;
    }

    private List<String> statusList(JsonNode node, Set<String> defaults) {
        if (!node.isArray()) {
            return List.copyOf(defaults);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String status = normalizeStatus(item.asText(null));
            if (status != null) {
                values.add(status);
            }
        }
        return values.isEmpty() ? List.copyOf(defaults) : List.copyOf(values);
    }

    private boolean isSameOrChild(Path candidate, Path parent) {
        if (candidate == null || parent == null) {
            return false;
        }
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        Path normalizedParent = parent.toAbsolutePath().normalize();
        return normalizedCandidate.equals(normalizedParent) || normalizedCandidate.startsWith(normalizedParent);
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

    private void putIfPresent(ObjectNode payload, String field, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(field, value);
        }
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Stacks monitoring interrupted", e);
        }
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePathText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String trimLeadingSlash(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private record SubmittedStacksTask(String taskId, String statusUrl, JsonNode responseBody) {
    }

    private record StacksStatus(String status, Integer progressPercent, String filePath, String errorMessage, JsonNode raw) {
    }

    private record StacksConfig(URI submitUri,
                                String statusUrlTemplate,
                                String apiKey,
                                String apiKeyHeader,
                                String authorizationScheme,
                                String username,
                                String password,
                                int requestTimeoutSeconds,
                                int pollIntervalSeconds,
                                int timeoutMinutes,
                                String remoteStagingPath,
                                List<String> completedStatuses,
                                List<String> failedStatuses) {

        URI resolveUrl(String value) {
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return URI.create(value);
            }
            URI base = submitUri.resolve(".");
            return base.resolve(value);
        }

        String resolveRemoteStagingPath(Path stagingDir, Long jobId) {
            String template = remoteStagingPath == null || remoteStagingPath.isBlank() ? "{stagingDir}" : remoteStagingPath;
            return template
                    .replace("{stagingDir}", stagingDir.toAbsolutePath().toString())
                    .replace("{jobId}", String.valueOf(jobId));
        }
    }
}
