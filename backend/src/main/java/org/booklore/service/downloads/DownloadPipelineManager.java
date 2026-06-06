package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.entity.DownloadResultEntity;
import org.booklore.model.entity.DownloadSearchEntity;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.*;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.repository.DownloadResultRepository;
import org.booklore.repository.DownloadSearchRepository;
import org.booklore.repository.DownloadSourceRepository;
import org.booklore.service.downloads.adapter.DownloadAdapterRegistry;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;
import org.booklore.service.downloads.dto.DownloadScoreBreakdown;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadException;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.booklore.service.downloads.exception.DownloadValidationException;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.DownloadExecutor;
import org.booklore.service.downloads.executor.DownloadExecutorRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadPipelineManager {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final String DOWNLOADS_DIR = ".downloads";
    private static final int MIN_DOWNLOADABLE_SCORE = 50;
    private static final Duration STALE_RETRY_AFTER = Duration.ofMinutes(10);
    private static final Set<DownloadJobStatus> RETRYABLE_TERMINAL_STATUSES = EnumSet.of(
            DownloadJobStatus.FAILED,
            DownloadJobStatus.CANCELLED
    );
    private static final Set<DownloadJobStatus> ACTIVE_STATUSES = EnumSet.of(
            DownloadJobStatus.QUEUED,
            DownloadJobStatus.SEARCHING,
            DownloadJobStatus.SCORING,
            DownloadJobStatus.DOWNLOADING,
            DownloadJobStatus.VALIDATING,
            DownloadJobStatus.STAGED,
            DownloadJobStatus.DELIVERING,
            DownloadJobStatus.AUTO_FINALIZING
    );
    private static final List<DownloadJobStatus> REUSABLE_JOB_STATUSES = List.of(
            DownloadJobStatus.QUEUED,
            DownloadJobStatus.SEARCHING,
            DownloadJobStatus.SCORING,
            DownloadJobStatus.DOWNLOADING,
            DownloadJobStatus.VALIDATING,
            DownloadJobStatus.STAGED,
            DownloadJobStatus.DELIVERING,
            DownloadJobStatus.AUTO_FINALIZING,
            DownloadJobStatus.PENDING_REVIEW,
            DownloadJobStatus.COMPLETED
    );

    private final AppProperties appProperties;
    private final DownloadSourceRepository sourceRepository;
    private final DownloadSearchRepository searchRepository;
    private final DownloadResultRepository resultRepository;
    private final DownloadJobRepository jobRepository;
    private final DownloadAdapterRegistry adapterRegistry;
    private final DownloadExecutorRegistry executorRegistry;
    private final DownloadScoringService scoringService;
    private final DownloadNamingService namingService;
    private final DownloadTargetResolver targetResolver;
    private final DownloadedCbxMetadataService downloadedCbxMetadataService;
    private final BookdropDeliveryService bookdropDeliveryService;
    private final DownloadQueryIntentParser queryIntentParser;
    private final DownloadCanonicalResolver canonicalResolver;
    private final ObjectMapper objectMapper;

    public List<DownloadCanonicalResolver.CanonicalCandidate> resolveCandidates(DownloadSearchCriteria criteria) {
        return canonicalResolver.resolveCandidates(queryIntentParser.enrich(criteria));
    }

    @Transactional
    public DownloadSearchEntity search(DownloadSearchCriteria criteria) {
        criteria = queryIntentParser.enrich(criteria);
        criteria = canonicalResolver.resolve(criteria);
        DownloadSearchCriteria.CanonicalSelection canonicalSelection = criteria.getCanonicalSelection();
        DownloadSearchEntity search = searchRepository.save(DownloadSearchEntity.builder()
                .query(criteria.effectiveQuery())
                .title(criteria.getTitle())
                .author(criteria.getAuthor())
                .isbn(criteria.getIsbn())
                .seriesName(criteria.getSeriesName())
                .seriesNumber(criteria.getSeriesNumber())
                .contentKind(criteria.getContentKind())
                .preferredFormatsJson(writeJson(criteria.getPreferredFormats()))
                .canonicalProvider(canonicalProvider(canonicalSelection))
                .canonicalContentKind(canonicalSelection == null ? null : canonicalSelection.contentKind())
                .canonicalTitle(canonicalSelection == null ? null : canonicalSelection.resolvedTitle())
                .canonicalAuthor(canonicalSelection == null ? null : canonicalSelection.resolvedAuthor())
                .canonicalIsbn(canonicalSelection == null ? null : canonicalSelection.resolvedIsbn())
                .canonicalSeriesName(canonicalSelection == null ? null : canonicalSelection.resolvedSeriesName())
                .canonicalSeriesNumber(canonicalSelection == null ? null : canonicalSelection.seriesNumber())
                .canonicalSequenceNumberType(canonicalSelection == null ? null : canonicalSelection.sequenceNumberType())
                .canonicalConfidence(canonicalSelection == null ? null : canonicalSelection.confidence())
                .canonicalCoverUrl(canonicalSelection == null ? null : canonicalSelection.coverUrl())
                .status(DownloadSearchStatus.RUNNING)
                .build());

        try {
            List<DownloadSourceEntity> sources = sourceRepository.findAllByEnabledTrueOrderByPriorityAscNameAsc();
            List<String> sourceErrors = new ArrayList<>();
            int resultCount = 0;
            for (SourceSearchOutcome outcome : runSourceSearches(sources, criteria)) {
                resultCount += outcome.results().size();
                if (outcome.errorMessage() != null) {
                    sourceErrors.add(outcome.errorMessage());
                }
                for (NormalizedDownloadResult normalized : outcome.results()) {
                    DownloadScoreBreakdown score = scoringService.score(criteria, normalized);
                    resultRepository.save(toEntity(search, outcome.source(), normalized, score));
                }
            }
            if (sources.isEmpty()) {
                search.setErrorMessage("No enabled download sources are configured");
            } else if (resultCount == 0 && !sourceErrors.isEmpty()) {
                search.setErrorMessage(String.join(" | ", sourceErrors));
            }
            search.setStatus(DownloadSearchStatus.COMPLETED);
            return searchRepository.save(search);
        } catch (Exception e) {
            search.setStatus(DownloadSearchStatus.FAILED);
            search.setErrorMessage(e.getMessage());
            searchRepository.save(search);
            throw e;
        }
    }

    @Transactional
    public DownloadJobEntity queueBestMatch(DownloadSearchCriteria criteria,
                                            Long targetLibraryId,
                                            Long targetLibraryPathId,
                                            boolean autoFinalize,
                                            int confidenceThreshold) {
        DownloadSearchEntity search = search(criteria);
        DownloadResultEntity best = resultRepository.findAllBySearchIdOrderByScoreDescIdAsc(search.getId())
                .stream()
                .max(Comparator.comparing(DownloadResultEntity::getScore, Comparator.nullsFirst(Integer::compareTo)))
                .orElseThrow(() -> new DownloadException("No downloadable result found for query: " + criteria.effectiveQuery()));
        if (best.getScore() == null || best.getScore() < MIN_DOWNLOADABLE_SCORE) {
            throw new DownloadException("No confident downloadable result found for query: " + criteria.effectiveQuery());
        }
        Optional<DownloadJobEntity> reusableJob = reusableJobFor(best);
        if (reusableJob.isPresent()) {
            log.info("Reusing existing download job {} for result fingerprint {}", reusableJob.get().getId(), resultFingerprint(best));
            return reusableJob.get();
        }
        DownloadTargetResolver.ResolvedTarget target = targetResolver.resolve(targetLibraryId, targetLibraryPathId, autoFinalize, best.getFormat());

        DownloadJobEntity job = DownloadJobEntity.builder()
                .search(best.getSearch())
                .result(best)
                .source(best.getSource())
                .status(DownloadJobStatus.QUEUED)
                .confidenceScore(best.getScore())
                .autoFinalize(autoFinalize)
                .confidenceThreshold(confidenceThreshold)
                .fallbackEnabled(Boolean.TRUE)
                .targetLibraryId(target.libraryId())
                .targetLibraryPathId(target.libraryPathId())
                .build();
        return jobRepository.save(job);
    }

    public DownloadJobEntity acquireBestMatch(DownloadSearchCriteria criteria,
                                              Long targetLibraryId,
                                              Long targetLibraryPathId,
                                              boolean autoFinalize,
                                              int confidenceThreshold) {
        DownloadJobEntity job = queueBestMatch(criteria, targetLibraryId, targetLibraryPathId, autoFinalize, confidenceThreshold);
        return processQueuedJob(job.getId());
    }

    @Transactional
    public DownloadJobEntity queueResult(Long resultId,
                                         Long targetLibraryId,
                                         Long targetLibraryPathId,
                                         boolean autoFinalize,
                                         int confidenceThreshold) {
        DownloadResultEntity result = resultRepository.findWithSearchAndSourceById(resultId)
                .orElseThrow(() -> new DownloadException("Download result not found: " + resultId));
        Optional<DownloadJobEntity> reusableJob = reusableJobFor(result);
        if (reusableJob.isPresent()) {
            log.info("Reusing existing download job {} for selected result fingerprint {}", reusableJob.get().getId(), resultFingerprint(result));
            return reusableJob.get();
        }
        DownloadTargetResolver.ResolvedTarget target = targetResolver.resolve(targetLibraryId, targetLibraryPathId, autoFinalize, result.getFormat());

        DownloadJobEntity job = DownloadJobEntity.builder()
                .search(result.getSearch())
                .result(result)
                .source(result.getSource())
                .status(DownloadJobStatus.QUEUED)
                .confidenceScore(result.getScore())
                .autoFinalize(autoFinalize)
                .confidenceThreshold(confidenceThreshold)
                .fallbackEnabled(Boolean.FALSE)
                .targetLibraryId(target.libraryId())
                .targetLibraryPathId(target.libraryPathId())
                .build();
        return jobRepository.save(job);
    }

    public DownloadJobEntity acquireResult(Long resultId,
                                           Long targetLibraryId,
                                           Long targetLibraryPathId,
                                           boolean autoFinalize,
                                           int confidenceThreshold) {
        DownloadJobEntity job = queueResult(resultId, targetLibraryId, targetLibraryPathId, autoFinalize, confidenceThreshold);
        return processQueuedJob(job.getId());
    }

    @Transactional
    public DownloadJobEntity retryJob(Long jobId) {
        DownloadJobEntity previous = jobRepository.findWithSearchAndResultAndSourceById(jobId)
                .orElseThrow(() -> new DownloadException("Download job not found: " + jobId));
        if (!isRetryable(previous)) {
            throw new DownloadException("Download job " + jobId + " cannot be retried from status " + previous.getStatus());
        }

        if (ACTIVE_STATUSES.contains(previous.getStatus())) {
            previous.setStatus(DownloadJobStatus.FAILED);
            previous.setCompletedAt(Instant.now());
            previous.setErrorMessage("Marked failed before retry because the job stopped reporting progress.");
            jobRepository.save(previous);
        }

        DownloadResultEntity result = previous.getResult();
        DownloadTargetResolver.ResolvedTarget target = targetResolver.resolve(
                previous.getTargetLibraryId(),
                previous.getTargetLibraryPathId(),
                Boolean.TRUE.equals(previous.getAutoFinalize()),
                result.getFormat()
        );
        DownloadJobEntity retry = DownloadJobEntity.builder()
                .search(previous.getSearch())
                .result(result)
                .source(previous.getSource())
                .status(DownloadJobStatus.QUEUED)
                .confidenceScore(previous.getConfidenceScore())
                .autoFinalize(Boolean.TRUE.equals(previous.getAutoFinalize()))
                .confidenceThreshold(previous.getConfidenceThreshold() == null ? 90 : previous.getConfidenceThreshold())
                .fallbackEnabled(Boolean.TRUE)
                .targetLibraryId(target.libraryId())
                .targetLibraryPathId(target.libraryPathId())
                .externalTaskId(previous.getExternalTaskId())
                .externalTaskType(previous.getExternalTaskType())
                .build();
        DownloadJobEntity saved = jobRepository.save(retry);
        if (previous.getErrorMessage() != null && previous.getErrorMessage().startsWith("Marked failed before retry")) {
            previous.setErrorMessage("Marked failed before retry as job #" + saved.getId() + " because the job stopped reporting progress.");
            jobRepository.save(previous);
        }
        return saved;
    }

    public DownloadJobEntity processQueuedJob(Long jobId) {
        DownloadJobEntity job = jobRepository.findWithSearchAndResultAndSourceById(jobId)
                .orElseThrow(() -> new DownloadException("Download job not found: " + jobId));
        List<Long> attemptedResultIds = new ArrayList<>();
        while (true) {
            attemptedResultIds.add(job.getResult().getId());
            try {
                return processJobAttempt(job);
            } catch (Exception e) {
                log.error("Download job {} failed on result {}: {}", jobId, job.getResult().getId(), e.getMessage(), e);
                if (isSupersededByRetry(jobId)) {
                    return jobRepository.findById(jobId).orElse(job);
                }
                Optional<DownloadResultEntity> fallback = fallbackResult(job, attemptedResultIds, e);
                if (fallback.isPresent()) {
                    DownloadResultEntity next = fallback.get();
                    log.warn("Download job {} falling back from result {} to result {} after failure: {}",
                            jobId, job.getResult().getId(), next.getId(), e.getMessage());
                    job = switchJobToFallbackResult(job, next);
                    continue;
                }
                job.setCompletedAt(Instant.now());
                updateJob(job, DownloadJobStatus.FAILED, job.getProgressPercent(), e.getMessage());
                return jobRepository.save(job);
            }
        }
    }

    private DownloadJobEntity processJobAttempt(DownloadJobEntity job) throws Exception {
        NormalizedDownloadResult result = toNormalizedResult(job.getResult());
        DownloadExecutor executor = executorRegistry.executorFor(result.getAcquisitionType());

        Path stagingDir = createStagingDir(job.getId());
        Path partFile = stagingDir.resolve(UUID.randomUUID() + ".part");
        updateJob(job, DownloadJobStatus.DOWNLOADING, 0, null);
        job.setStagingDir(stagingDir.toString());
        job.setPartFilePath(partFile.toString());
        job.setLastProgressAt(Instant.now());
        jobRepository.save(job);

        Path downloadedFile = executor.download(DownloadExecutionRequest.builder()
                .job(job)
                .source(job.getSource())
                .result(result)
                .stagingDir(stagingDir)
                .targetPartFile(partFile)
                .build(), percent -> updateProgress(job.getId(), percent));
        throwIfSupersededByRetry(job);
        if (downloadedFile != null && !downloadedFile.equals(partFile)) {
            partFile = downloadedFile;
            job.setPartFilePath(partFile.toString());
            jobRepository.save(job);
        }

        updateJob(job, DownloadJobStatus.VALIDATING, 100, null);
        DownloadFormat detectedFormat = validateDownloadedFile(partFile, result);
        if (detectedFormat != result.getFormat()) {
            result = result.toBuilder().format(detectedFormat).build();
        }
        String finalFileName = namingService.buildFinalFileName(result, detectedFormat);
        downloadedCbxMetadataService.embedIfApplicable(partFile, result, detectedFormat);
        Path stagedFile = stagingDir.resolve(finalFileName + ".staged");
        Files.move(partFile, stagedFile, StandardCopyOption.REPLACE_EXISTING);
        job.setStagedFilePath(stagedFile.toString());
        updateJob(job, DownloadJobStatus.STAGED, 100, null);

        updateJob(job, DownloadJobStatus.DELIVERING, 100, null);
        throwIfSupersededByRetry(job);
        BookdropDeliveryService.DeliveryResult deliveryResult = bookdropDeliveryService.deliver(job, result, stagedFile, finalFileName);
        job.setDeliveredFilePath(deliveryResult.finalPath().toString());
        job.setCompletedAt(Instant.now());
        updateJob(job, deliveryResult.autoFinalized() ? DownloadJobStatus.COMPLETED : DownloadJobStatus.PENDING_REVIEW, 100, null);
        cleanupEmptyStagingDirectories(stagingDir);
        return jobRepository.save(job);
    }

    private Optional<DownloadJobEntity> reusableJobFor(DownloadResultEntity result) {
        if (result == null || result.getSource() == null || result.getSource().getId() == null) {
            return Optional.empty();
        }
        String externalId = trimToNull(result.getExternalId());
        String detailsUrl = trimToNull(result.getDetailsUrl());
        String downloadUrl = trimToNull(result.getDownloadUrl());
        if (externalId == null && detailsUrl == null && downloadUrl == null) {
            return Optional.empty();
        }
        return jobRepository.findReusableByResultFingerprint(
                        result.getSource().getId(),
                        externalId,
                        detailsUrl,
                        downloadUrl,
                        REUSABLE_JOB_STATUSES
                )
                .stream()
                .findFirst();
    }

    private String resultFingerprint(DownloadResultEntity result) {
        return firstNonBlank(result.getExternalId(), result.getDetailsUrl(), result.getDownloadUrl(), String.valueOf(result.getId()));
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private Optional<DownloadResultEntity> fallbackResult(DownloadJobEntity job, List<Long> attemptedResultIds, Exception failure) {
        if (!shouldAttemptFallback(job, failure) || job.getSearch() == null || job.getSearch().getId() == null) {
            return Optional.empty();
        }
        return resultRepository.findAllBySearchIdOrderByScoreDescIdAsc(job.getSearch().getId()).stream()
                .filter(candidate -> candidate.getId() != null && !attemptedResultIds.contains(candidate.getId()))
                .filter(candidate -> candidate.getScore() != null && candidate.getScore() >= MIN_DOWNLOADABLE_SCORE)
                .findFirst();
    }

    private boolean shouldAttemptFallback(DownloadJobEntity job, Exception failure) {
        if (Boolean.TRUE.equals(job.getFallbackEnabled())) {
            return true;
        }
        if (job == null || job.getResult() == null || failure == null) {
            return false;
        }
        DownloadAcquisitionType acquisitionType = job.getResult().getAcquisitionType();
        return acquisitionType == DownloadAcquisitionType.EXTERNAL_STACKS
                && isStacksMirrorFailure(failure.getMessage());
    }

    private boolean isStacksMirrorFailure(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("stacks download failed")
                && normalized.contains("mirror ")
                && normalized.contains(" failed");
    }

    private DownloadJobEntity switchJobToFallbackResult(DownloadJobEntity job, DownloadResultEntity result) {
        job.setResult(result);
        job.setSource(result.getSource());
        job.setStatus(DownloadJobStatus.QUEUED);
        job.setProgressPercent(0);
        job.setConfidenceScore(result.getScore());
        job.setStagingDir(null);
        job.setPartFilePath(null);
        job.setExternalTaskId(null);
        job.setExternalTaskType(null);
        job.setStagedFilePath(null);
        job.setDeliveredFilePath(null);
        job.setErrorMessage(null);
        job.setCompletedAt(null);
        job.setLastProgressAt(null);
        return jobRepository.save(job);
    }

    private List<SourceSearchOutcome> runSourceSearches(List<DownloadSourceEntity> sources, DownloadSearchCriteria criteria) {
        if (sources.size() <= 1) {
            return sources.stream()
                    .map(source -> runSourceSearch(source, criteria))
                    .toList();
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<SourceSearchOutcome>> futures = sources.stream()
                    .map(source -> CompletableFuture.supplyAsync(() -> runSourceSearch(source, criteria), executor))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }
    }

    private SourceSearchOutcome runSourceSearch(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        try {
            DownloadSourceAdapter adapter = adapterRegistry.adapterFor(source);
            List<NormalizedDownloadResult> normalizedResults = adapter.search(source, criteria);
            return new SourceSearchOutcome(source, normalizedResults, null);
        } catch (DownloadSourceException e) {
            log.warn("Download source '{}' failed during search: {}", source.getName(), e.getMessage());
            return new SourceSearchOutcome(source, List.of(), source.getName() + ": " + e.getMessage());
        } catch (Exception e) {
            log.warn("Download source '{}' failed unexpectedly during search: {}", source.getName(), e.getMessage(), e);
            return new SourceSearchOutcome(source, List.of(), source.getName() + ": " + e.getMessage());
        }
    }

    private DownloadResultEntity toEntity(DownloadSearchEntity search,
                                          DownloadSourceEntity source,
                                          NormalizedDownloadResult result,
                                          DownloadScoreBreakdown score) {
        return DownloadResultEntity.builder()
                .search(search)
                .source(source)
                .externalId(DownloadPersistenceSanitizer.externalId(result.getSourceResultId()))
                .title(DownloadPersistenceSanitizer.requiredText(result.getTitle(), "Untitled", DownloadPersistenceSanitizer.TITLE_MAX_LENGTH))
                .authorsJson(writeJson(result.getAuthors()))
                .seriesName(DownloadPersistenceSanitizer.optionalText(result.getSeriesName(), DownloadPersistenceSanitizer.SERIES_NAME_MAX_LENGTH))
                .seriesNumber(result.getSeriesNumber())
                .publishedYear(result.getPublishedYear())
                .isbn(DownloadPersistenceSanitizer.optionalText(result.getIsbn(), DownloadPersistenceSanitizer.ISBN_MAX_LENGTH))
                .language(DownloadPersistenceSanitizer.optionalText(result.getLanguage(), DownloadPersistenceSanitizer.LANGUAGE_MAX_LENGTH))
                .format(result.getFormat())
                .contentKind(result.getContentKind())
                .acquisitionType(result.getAcquisitionType())
                .sizeBytes(result.getSizeBytes())
                .downloadUrl(result.getDownloadUrl())
                .detailsUrl(result.getDetailsUrl())
                .requiresFlareSolverr(result.isRequiresFlareSolverr())
                .score(score.getScore())
                .scoreReasons(String.join("\n", score.getReasons()))
                .rawJson(result.getRawJson())
                .build();
    }

    private NormalizedDownloadResult toNormalizedResult(DownloadResultEntity entity) {
        return NormalizedDownloadResult.builder()
                .sourceResultId(entity.getExternalId())
                .title(entity.getTitle())
                .authors(readStringList(entity.getAuthorsJson()))
                .seriesName(entity.getSeriesName())
                .seriesNumber(entity.getSeriesNumber())
                .publishedYear(entity.getPublishedYear())
                .isbn(entity.getIsbn())
                .language(entity.getLanguage())
                .format(entity.getFormat())
                .contentKind(entity.getContentKind())
                .acquisitionType(entity.getAcquisitionType())
                .sizeBytes(entity.getSizeBytes())
                .downloadUrl(entity.getDownloadUrl())
                .detailsUrl(entity.getDetailsUrl())
                .requiresFlareSolverr(Boolean.TRUE.equals(entity.getRequiresFlareSolverr()))
                .rawJson(entity.getRawJson())
                .build();
    }

    private Path createStagingDir(Long jobId) throws IOException {
        Path dir = Path.of(appProperties.getBookdropFolder(), DOWNLOADS_DIR, String.valueOf(jobId));
        Files.createDirectories(dir);
        return dir;
    }

    private DownloadFormat validateDownloadedFile(Path partFile, NormalizedDownloadResult result) throws IOException {
        if (Files.notExists(partFile)) {
            throw new DownloadValidationException("Downloaded file is missing from staging");
        }
        long size = Files.size(partFile);
        if (size <= 0) {
            throw new DownloadValidationException("Downloaded file is empty");
        }
        if (result.getSizeBytes() != null && result.getSizeBytes() > 0 && size < Math.max(1024L, result.getSizeBytes() / 10L)) {
            throw new DownloadValidationException("Downloaded file is much smaller than expected");
        }

        DownloadFormat format = DownloadFormat.fromFileName(partFile.getFileName().toString())
                .or(() -> DownloadFormat.fromFileName(result.getDownloadUrl()))
                .orElse(result.getFormat() == null ? DownloadFormat.UNKNOWN : result.getFormat());
        if (format == DownloadFormat.UNKNOWN || format.extension().isBlank()) {
            throw new DownloadValidationException("Unsupported or unknown downloaded format");
        }
        if (org.booklore.model.enums.BookFileExtension.fromFileName("download." + format.extension()).isEmpty()) {
            throw new DownloadValidationException("Downloaded format is not supported by BookLore: " + format);
        }
        return format;
    }

    private void updateProgress(Long jobId, int percent) {
        if (isSupersededByRetry(jobId)) {
            return;
        }
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProgressPercent(Math.max(0, Math.min(100, percent)));
            job.setLastProgressAt(Instant.now());
            jobRepository.save(job);
        });
    }

    private void updateJob(DownloadJobEntity job, DownloadJobStatus status, Integer progress, String errorMessage) {
        if (isSupersededByRetry(job.getId())) {
            log.info("Skipping update of superseded download job {} to {}", job.getId(), status);
            return;
        }
        job.setStatus(status);
        if (progress != null) job.setProgressPercent(Math.max(0, Math.min(100, progress)));
        if (progress != null || status == DownloadJobStatus.DOWNLOADING) job.setLastProgressAt(Instant.now());
        job.setErrorMessage(errorMessage);
        jobRepository.save(job);
    }

    private void cleanupEmptyStagingDirectories(Path stagingDir) {
        try (var paths = Files.walk(stagingDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(Files::isDirectory)
                    .forEach(this::deleteEmptyDirectoryQuietly);
        } catch (IOException e) {
            log.debug("Staging directory {} could not be scanned for cleanup", stagingDir, e);
        }
    }

    private void deleteEmptyDirectoryQuietly(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            log.debug("Staging directory {} was not empty or could not be deleted", directory);
        }
    }

    private void throwIfSupersededByRetry(DownloadJobEntity job) {
        if (isSupersededByRetry(job.getId())) {
            throw new DownloadException("Download job " + job.getId() + " was superseded by a retry");
        }
    }

    private boolean isSupersededByRetry(Long jobId) {
        if (jobId == null) {
            return false;
        }
        return jobRepository.findById(jobId)
                .filter(job -> job.getStatus() == DownloadJobStatus.FAILED)
                .map(DownloadJobEntity::getErrorMessage)
                .filter(message -> message != null && message.startsWith("Marked failed before retry"))
                .isPresent();
    }

    private boolean isRetryable(DownloadJobEntity job) {
        if (RETRYABLE_TERMINAL_STATUSES.contains(job.getStatus())) {
            return true;
        }
        if (!ACTIVE_STATUSES.contains(job.getStatus())) {
            return false;
        }
        Instant lastProgress = job.getLastProgressAt();
        if (lastProgress == null) {
            lastProgress = job.getUpdatedAt();
        }
        if (lastProgress == null) {
            lastProgress = job.getCreatedAt();
        }
        return lastProgress != null && lastProgress.isBefore(Instant.now().minus(STALE_RETRY_AFTER));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new DownloadException("Failed to serialize download JSON", e);
        }
    }

    private String canonicalProvider(DownloadSearchCriteria.CanonicalSelection selection) {
        if (selection == null || selection.provider() == null || selection.provider().isBlank()) {
            return null;
        }
        return selection.provider();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private record SourceSearchOutcome(DownloadSourceEntity source,
                                       List<NormalizedDownloadResult> results,
                                       String errorMessage) {
    }
}
