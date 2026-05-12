package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.request.downloads.DownloadAcquireRequest;
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
import org.booklore.service.downloads.DownloadJobCleanupService;
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
                .map(this::toResultResponse)
                .toList();
        return new DownloadSearchResponse(
                search.getId(),
                search.getStatus(),
                search.getQuery(),
                search.getErrorMessage(),
                results
        );
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

    @Operation(summary = "Acquire best matching result synchronously")
    @ApiResponse(responseCode = "200", description = "Download job processed")
    @PostMapping("/acquire")
    public DownloadJobResponse acquireBestMatch(@Parameter(description = "Download acquisition request") @RequestBody DownloadAcquireRequest request) {
        return toJobResponse(pipelineManager.acquireBestMatch(
                toCriteria(request),
                request.getTargetLibraryId(),
                request.getTargetLibraryPathId(),
                Boolean.TRUE.equals(request.getAutoFinalize()),
                request.getConfidenceThreshold() == null ? 90 : request.getConfidenceThreshold()
        ));
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

    @Operation(summary = "Acquire a selected download result synchronously")
    @ApiResponse(responseCode = "200", description = "Selected download job processed")
    @PostMapping("/results/{resultId}/acquire")
    public DownloadJobResponse acquireResult(@PathVariable Long resultId,
                                             @Parameter(description = "Selected result acquisition request") @RequestBody DownloadResultAcquireRequest request) {
        return toJobResponse(pipelineManager.acquireResult(
                resultId,
                request.getTargetLibraryId(),
                request.getTargetLibraryPathId(),
                Boolean.TRUE.equals(request.getAutoFinalize()),
                request.getConfidenceThreshold() == null ? 90 : request.getConfidenceThreshold()
        ));
    }

    @Operation(summary = "Process a queued download job")
    @ApiResponse(responseCode = "200", description = "Download job processed")
    @PostMapping("/jobs/{jobId}/process")
    public DownloadJobResponse processJob(@PathVariable Long jobId) {
        return toJobResponse(pipelineManager.processQueuedJob(jobId));
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
                .query(request.getQuery())
                .title(request.getTitle())
                .author(request.getAuthor())
                .isbn(request.getIsbn())
                .seriesName(request.getSeriesName())
                .seriesNumber(request.getSeriesNumber())
                .contentKind(request.getContentKind() == null ? DownloadContentKind.BOOK : request.getContentKind())
                .preferredFormats(request.getPreferredFormats() == null ? List.of() : request.getPreferredFormats())
                .directUrl(request.getDirectUrl())
                .maxResults(request.getMaxResults() == null ? 25 : Math.max(1, request.getMaxResults()))
                .build();
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
                                         List<DownloadResultResponse> results) {
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
