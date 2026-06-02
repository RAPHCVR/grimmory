package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.request.downloads.DownloadAcquireRequest;
import org.booklore.model.dto.request.downloads.DownloadCanonicalSelectionRequest;
import org.booklore.model.dto.request.downloads.DownloadResultAcquireRequest;
import org.booklore.model.dto.request.downloads.DownloadSearchRequest;
import org.booklore.model.dto.request.downloads.DownloadSourceRequest;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.entity.DownloadResultEntity;
import org.booklore.model.entity.DownloadSearchEntity;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.*;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.repository.DownloadResultRepository;
import org.booklore.repository.DownloadSourceRepository;
import org.booklore.service.downloads.DownloadCanonicalResolver;
import org.booklore.service.downloads.DownloadJobCleanupService;
import org.booklore.service.downloads.DownloadJobRunner;
import org.booklore.service.downloads.DownloadPipelineManager;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Tag(name = "Downloads", description = "Internal acquisition pipeline endpoints")
@RestController
@RequestMapping("/api/v1/downloads")
@RequiredArgsConstructor
@PreAuthorize("@securityUtil.isAdmin()")
public class DownloadController {

    private final DownloadSourceRepository sourceRepository;
    private final DownloadResultRepository resultRepository;
    private final DownloadJobRepository jobRepository;
    private final DownloadPipelineManager pipelineManager;
    private final DownloadJobRunner jobRunner;
    private final DownloadJobCleanupService cleanupService;

    @Operation(summary = "List download sources")
    @ApiResponse(responseCode = "200", description = "Download sources returned successfully")
    @GetMapping("/sources")
    public List<DownloadSourceResponse> listSources() {
        return sourceRepository.findAll(Sort.by(Sort.Order.asc("priority"), Sort.Order.asc("name")))
                .stream()
                .map(this::toSourceResponse)
                .toList();
    }

    @Operation(summary = "Create a download source")
    @ApiResponse(responseCode = "200", description = "Download source created successfully")
    @PostMapping("/sources")
    public DownloadSourceResponse createSource(@Parameter(description = "Download source request") @RequestBody @Valid DownloadSourceRequest request) {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name(request.getName())
                .type(request.getType())
                .credentialsJson(request.getCredentialsJson())
                .configJson(request.getConfigJson())
                .enabled(Boolean.TRUE.equals(request.getEnabled()))
                .priority(request.getPriority() == null ? 100 : request.getPriority())
                .build();
        return toSourceResponse(sourceRepository.save(source));
    }

    @Operation(summary = "Update a download source")
    @ApiResponse(responseCode = "200", description = "Download source updated successfully")
    @PutMapping("/sources/{sourceId}")
    public DownloadSourceResponse updateSource(@PathVariable Long sourceId,
                                               @Parameter(description = "Download source request") @RequestBody @Valid DownloadSourceRequest request) {
        DownloadSourceEntity source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Download source not found: " + sourceId));
        source.setName(request.getName());
        source.setType(request.getType());
        source.setCredentialsJson(request.getCredentialsJson());
        source.setConfigJson(request.getConfigJson());
        source.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        source.setPriority(request.getPriority() == null ? 100 : request.getPriority());
        return toSourceResponse(sourceRepository.save(source));
    }

    @Operation(summary = "Search download sources")
    @ApiResponse(responseCode = "200", description = "Search completed")
    @PostMapping("/search")
    public DownloadSearchResponse search(@Parameter(description = "Download search request") @RequestBody DownloadSearchRequest request) {
        DownloadSearchEntity search = pipelineManager.search(toCriteria(request));
        List<DownloadResultResponse> results = resultRepository.findAllBySearchIdOrderByScoreDescIdAsc(search.getId())
                .stream()
                .filter(result -> result.getScore() != null && result.getScore() > 0)
                .map(this::toResultResponse)
                .toList();
        return new DownloadSearchResponse(
                search.getId(),
                search.getStatus(),
                search.getQuery(),
                search.getErrorMessage(),
                toCanonicalSelectionResponse(search),
                results
        );
    }

    @Operation(summary = "Resolve canonical work candidates")
    @ApiResponse(responseCode = "200", description = "Canonical candidates returned successfully")
    @PostMapping("/resolve")
    public List<DownloadCanonicalResolver.CanonicalCandidate> resolve(@Parameter(description = "Download canonical resolve request") @RequestBody DownloadSearchRequest request) {
        return pipelineManager.resolveCandidates(toCriteria(request));
    }

    @Operation(summary = "Queue best matching download result")
    @ApiResponse(responseCode = "200", description = "Download job queued")
    @PostMapping("/jobs")
    public DownloadJobResponse queueBestMatch(@Parameter(description = "Download acquisition request") @RequestBody DownloadAcquireRequest request) {
        return toJobResponse(pipelineManager.queueBestMatch(
                toCriteria(request),
                request.getTargetLibraryId(),
                request.getTargetLibraryPathId(),
                Boolean.TRUE.equals(request.getAutoFinalize()),
                request.getConfidenceThreshold() == null ? 90 : request.getConfidenceThreshold()
        ));
    }

    @Operation(summary = "Queue and start the best matching download result")
    @ApiResponse(responseCode = "200", description = "Download job queued and processing started")
    @PostMapping("/acquire")
    public DownloadJobResponse acquireBestMatch(@Parameter(description = "Download acquisition request") @RequestBody DownloadAcquireRequest request) {
        DownloadJobEntity job = pipelineManager.queueBestMatch(
                toCriteria(request),
                request.getTargetLibraryId(),
                request.getTargetLibraryPathId(),
                Boolean.TRUE.equals(request.getAutoFinalize()),
                request.getConfidenceThreshold() == null ? 90 : request.getConfidenceThreshold()
        );
        return toJobResponse(startIfQueued(job));
    }

    @Operation(summary = "Queue a selected download result")
    @ApiResponse(responseCode = "200", description = "Selected download job queued")
    @PostMapping("/results/{resultId}/jobs")
    public DownloadJobResponse queueResult(@PathVariable Long resultId,
                                           @Parameter(description = "Selected result acquisition request") @RequestBody DownloadResultAcquireRequest request) {
        return toJobResponse(pipelineManager.queueResult(
                resultId,
                request.getTargetLibraryId(),
                request.getTargetLibraryPathId(),
                Boolean.TRUE.equals(request.getAutoFinalize()),
                request.getConfidenceThreshold() == null ? 90 : request.getConfidenceThreshold()
        ));
    }

    @Operation(summary = "Queue and start a selected download result")
    @ApiResponse(responseCode = "200", description = "Selected download job queued and processing started")
    @PostMapping("/results/{resultId}/acquire")
    public DownloadJobResponse acquireResult(@PathVariable Long resultId,
                                             @Parameter(description = "Selected result acquisition request") @RequestBody DownloadResultAcquireRequest request) {
        DownloadJobEntity job = pipelineManager.queueResult(
                resultId,
                request.getTargetLibraryId(),
                request.getTargetLibraryPathId(),
                Boolean.TRUE.equals(request.getAutoFinalize()),
                request.getConfidenceThreshold() == null ? 90 : request.getConfidenceThreshold()
        );
        return toJobResponse(startIfQueued(job));
    }

    @Operation(summary = "Start processing a queued download job")
    @ApiResponse(responseCode = "200", description = "Download job processing started")
    @PostMapping("/jobs/{jobId}/process")
    public DownloadJobResponse processJob(@PathVariable Long jobId) {
        return toJobResponse(jobRunner.start(jobId));
    }

    @Operation(summary = "Retry a failed or stale download job")
    @ApiResponse(responseCode = "200", description = "Download job retried and processing started")
    @PostMapping("/jobs/{jobId}/retry")
    public DownloadJobResponse retryJob(@PathVariable Long jobId) {
        DownloadJobEntity retry = pipelineManager.retryJob(jobId);
        return toJobResponse(jobRunner.start(retry.getId()));
    }

    @Operation(summary = "Get a download job")
    @ApiResponse(responseCode = "200", description = "Download job returned successfully")
    @GetMapping("/jobs/{jobId}")
    public DownloadJobResponse getJob(@PathVariable Long jobId) {
        return toJobResponse(jobRepository.findWithSearchAndResultAndSourceById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Download job not found: " + jobId)));
    }

    @Operation(summary = "List download jobs")
    @ApiResponse(responseCode = "200", description = "Download jobs returned successfully")
    @GetMapping("/jobs")
    public List<DownloadJobResponse> listJobs(@RequestParam(required = false) DownloadJobStatus status) {
        List<DownloadJobEntity> jobs = status == null
                ? jobRepository.findAll(Sort.by(Sort.Order.desc("createdAt")))
                : jobRepository.findAllByStatusOrderByCreatedAtAsc(status);
        return jobs.stream().map(this::toJobResponse).toList();
    }

    @Operation(summary = "Delete a download source")
    @ApiResponse(responseCode = "204", description = "Download source deleted successfully")
    @DeleteMapping("/sources/{sourceId}")
    public ResponseEntity<Void> deleteSource(@PathVariable Long sourceId) {
        sourceRepository.deleteById(sourceId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Run download cleanup now")
    @ApiResponse(responseCode = "204", description = "Download cleanup triggered successfully")
    @PostMapping("/cleanup")
    public ResponseEntity<Void> cleanupNow() {
        cleanupService.cleanup();
        return ResponseEntity.noContent().build();
    }

    private DownloadSearchCriteria toCriteria(DownloadSearchRequest request) {
        return DownloadSearchCriteria.builder()
                .originalQuery(request.getQuery())
                .query(request.getQuery())
                .title(request.getTitle())
                .author(request.getAuthor())
                .isbn(request.getIsbn())
                .seriesName(request.getSeriesName())
                .seriesNumber(request.getSeriesNumber())
                .sequenceNumberType(request.getSequenceNumberType() == null ? DownloadSequenceNumberType.AUTO : request.getSequenceNumberType())
                .contentKind(request.getContentKind() == null ? DownloadContentKind.AUTO : request.getContentKind())
                .preferredFormats(request.getPreferredFormats() == null ? List.of() : request.getPreferredFormats())
                .directUrl(request.getDirectUrl())
                .canonicalSelection(toCanonicalSelection(request.getCanonicalSelection()))
                .maxResults(request.getMaxResults() == null ? 25 : Math.max(1, request.getMaxResults()))
                .build();
    }

    private DownloadJobEntity startIfQueued(DownloadJobEntity job) {
        return job.getStatus() == DownloadJobStatus.QUEUED ? jobRunner.start(job.getId()) : job;
    }

    private DownloadSearchCriteria.CanonicalSelection toCanonicalSelection(DownloadCanonicalSelectionRequest selection) {
        if (selection == null) {
            return null;
        }
        return new DownloadSearchCriteria.CanonicalSelection(
                trimToNull(selection.getProvider()),
                selection.getContentKind() == null ? DownloadContentKind.AUTO : selection.getContentKind(),
                trimToNull(selection.getTitle()),
                trimToNull(selection.getAuthor()),
                trimToNull(selection.getIsbn()),
                trimToNull(selection.getSeriesName()),
                selection.getConfidence(),
                trimToNull(selection.getQuery()),
                trimToNull(selection.getResolvedTitle()),
                trimToNull(selection.getResolvedAuthor()),
                trimToNull(selection.getResolvedIsbn()),
                trimToNull(selection.getResolvedSeriesName()),
                selection.getSeriesNumber(),
                selection.getSequenceNumberType() == null ? DownloadSequenceNumberType.AUTO : selection.getSequenceNumberType()
        );
    }

    private DownloadCanonicalSelectionResponse toCanonicalSelectionResponse(DownloadSearchEntity search) {
        if (search.getCanonicalProvider() == null
                && search.getCanonicalTitle() == null
                && search.getCanonicalSeriesName() == null
                && search.getCanonicalIsbn() == null) {
            return null;
        }
        return new DownloadCanonicalSelectionResponse(
                search.getCanonicalProvider(),
                search.getCanonicalContentKind(),
                search.getCanonicalTitle(),
                search.getCanonicalAuthor(),
                search.getCanonicalIsbn(),
                search.getCanonicalSeriesName(),
                search.getCanonicalConfidence(),
                search.getQuery(),
                search.getCanonicalTitle(),
                search.getCanonicalAuthor(),
                search.getCanonicalIsbn(),
                search.getCanonicalSeriesName(),
                search.getCanonicalSeriesNumber(),
                search.getCanonicalSequenceNumberType()
        );
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private DownloadSourceResponse toSourceResponse(DownloadSourceEntity source) {
        return new DownloadSourceResponse(
                source.getId(),
                source.getName(),
                source.getType(),
                source.getCredentialsJson(),
                source.getConfigJson(),
                source.getEnabled(),
                source.getPriority()
        );
    }

    private DownloadResultResponse toResultResponse(DownloadResultEntity result) {
        return new DownloadResultResponse(
                result.getId(),
                result.getSource().getId(),
                result.getSource().getName(),
                result.getExternalId(),
                result.getTitle(),
                result.getAuthorsJson(),
                result.getSeriesName(),
                result.getSeriesNumber(),
                result.getPublishedYear(),
                result.getIsbn(),
                result.getLanguage(),
                result.getFormat(),
                result.getContentKind(),
                result.getAcquisitionType(),
                result.getSizeBytes(),
                result.getDownloadUrl(),
                result.getDetailsUrl(),
                Boolean.TRUE.equals(result.getRequiresFlareSolverr()),
                result.getScore(),
                result.getScoreReasons()
        );
    }

    private DownloadJobResponse toJobResponse(DownloadJobEntity job) {
        return new DownloadJobResponse(
                job.getId(),
                job.getStatus(),
                job.getProgressPercent(),
                job.getConfidenceScore(),
                job.getExternalTaskType(),
                job.getExternalTaskId(),
                job.getStagingDir(),
                job.getPartFilePath(),
                job.getStagedFilePath(),
                job.getDeliveredFilePath(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getCompletedAt()
        );
    }

    public record DownloadSourceResponse(Long id,
                                         String name,
                                         DownloadSourceType type,
                                         String credentialsJson,
                                         String configJson,
                                         Boolean enabled,
                                         Integer priority) {
    }

    public record DownloadSearchResponse(Long id,
                                         DownloadSearchStatus status,
                                         String query,
                                         String errorMessage,
                                         DownloadCanonicalSelectionResponse canonicalSelection,
                                         List<DownloadResultResponse> results) {
    }

    public record DownloadCanonicalSelectionResponse(String provider,
                                                     DownloadContentKind contentKind,
                                                     String title,
                                                     String author,
                                                     String isbn,
                                                     String seriesName,
                                                     Double confidence,
                                                     String query,
                                                     String resolvedTitle,
                                                     String resolvedAuthor,
                                                     String resolvedIsbn,
                                                     String resolvedSeriesName,
                                                     Float seriesNumber,
                                                     DownloadSequenceNumberType sequenceNumberType) {
    }

    public record DownloadResultResponse(Long id,
                                         Long sourceId,
                                         String sourceName,
                                         String externalId,
                                         String title,
                                         String authorsJson,
                                         String seriesName,
                                         Float seriesNumber,
                                         Integer publishedYear,
                                         String isbn,
                                         String language,
                                         DownloadFormat format,
                                         DownloadContentKind contentKind,
                                         DownloadAcquisitionType acquisitionType,
                                         Long sizeBytes,
                                         String downloadUrl,
                                         String detailsUrl,
                                         boolean requiresFlareSolverr,
                                         Integer score,
                                         String scoreReasons) {
    }

    public record DownloadJobResponse(Long id,
                                      DownloadJobStatus status,
                                      Integer progressPercent,
                                      Integer confidenceScore,
                                      String externalTaskType,
                                      String externalTaskId,
                                      String stagingDir,
                                      String partFilePath,
                                      String stagedFilePath,
                                      String deliveredFilePath,
                                      String errorMessage,
                                      Instant createdAt,
                                      Instant updatedAt,
                                      Instant completedAt) {
    }
}
