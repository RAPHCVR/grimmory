package org.booklore.service.downloads;

import org.booklore.config.AppProperties;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.entity.DownloadResultEntity;
import org.booklore.model.entity.DownloadSearchEntity;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadJobStatus;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.repository.DownloadResultRepository;
import org.booklore.repository.DownloadSearchRepository;
import org.booklore.repository.DownloadSourceRepository;
import org.booklore.service.downloads.adapter.DownloadAdapterRegistry;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.DownloadExecutor;
import org.booklore.service.downloads.executor.DownloadExecutorRegistry;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadPipelineManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void validateDownloadedFile_prefersActualPayloadExtensionOverAdvertisedFormat() throws Exception {
        Path downloaded = tempDir.resolve("Wakfu Manga - Tome 1.pdf");
        Files.writeString(downloaded, "%PDF-1.4");

        DownloadPipelineManager manager = new DownloadPipelineManager(
                new AppProperties(),
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                mock(DownloadResultRepository.class),
                mock(DownloadJobRepository.class),
                mock(DownloadAdapterRegistry.class),
                mock(DownloadExecutorRegistry.class),
                mock(DownloadScoringService.class),
                mock(DownloadNamingService.class),
                mock(DownloadTargetResolver.class),
                mock(DownloadedCbxMetadataService.class),
                mock(BookdropDeliveryService.class),
                mock(DownloadQueryIntentParser.class),
                mock(DownloadCanonicalResolver.class),
                new ObjectMapper()
        );

        Method method = DownloadPipelineManager.class.getDeclaredMethod(
                "validateDownloadedFile",
                Path.class,
                NormalizedDownloadResult.class
        );
        method.setAccessible(true);

        DownloadFormat detected = (DownloadFormat) method.invoke(
                manager,
                downloaded,
                NormalizedDownloadResult.builder()
                        .title("Wakfu Manga - Tome 1")
                        .format(DownloadFormat.EPUB)
                        .build()
        );

        assertEquals(DownloadFormat.PDF, detected);
    }

    @Test
    void processQueuedJob_whenBestMatchFailsFallsBackToNextScoredResult() {
        AppProperties appProperties = new AppProperties();
        appProperties.setBookdropFolder(tempDir.toString());

        DownloadResultRepository resultRepository = mock(DownloadResultRepository.class);
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        DownloadExecutorRegistry executorRegistry = mock(DownloadExecutorRegistry.class);
        DownloadNamingService namingService = mock(DownloadNamingService.class);
        DownloadedCbxMetadataService downloadedCbxMetadataService = mock(DownloadedCbxMetadataService.class);
        BookdropDeliveryService bookdropDeliveryService = mock(BookdropDeliveryService.class);

        DownloadPipelineManager manager = new DownloadPipelineManager(
                appProperties,
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                resultRepository,
                jobRepository,
                mock(DownloadAdapterRegistry.class),
                executorRegistry,
                mock(DownloadScoringService.class),
                namingService,
                mock(DownloadTargetResolver.class),
                downloadedCbxMetadataService,
                bookdropDeliveryService,
                mock(DownloadQueryIntentParser.class),
                mock(DownloadCanonicalResolver.class),
                new ObjectMapper()
        );

        DownloadSourceEntity failingSource = source(1L, "Stacks", DownloadSourceType.ANNAS_ARCHIVE_API);
        DownloadSourceEntity fallbackSource = source(2L, "Direct", DownloadSourceType.DIRECT_URL);
        DownloadSearchEntity search = DownloadSearchEntity.builder()
                .id(10L)
                .query("public domain test")
                .contentKind(DownloadContentKind.BOOK)
                .build();
        DownloadResultEntity failingResult = result(100L, search, failingSource, "Mirror candidate", 100, DownloadAcquisitionType.EXTERNAL_STACKS);
        DownloadResultEntity fallbackResult = result(101L, search, fallbackSource, "Fallback candidate", 90, DownloadAcquisitionType.DIRECT_FILE);
        DownloadJobEntity job = DownloadJobEntity.builder()
                .id(55L)
                .search(search)
                .result(failingResult)
                .source(failingSource)
                .status(DownloadJobStatus.QUEUED)
                .confidenceScore(100)
                .autoFinalize(false)
                .confidenceThreshold(90)
                .fallbackEnabled(true)
                .build();

        DownloadExecutor failingExecutor = mock(DownloadExecutor.class);
        DownloadExecutor fallbackExecutor = mock(DownloadExecutor.class);

        when(jobRepository.findWithSearchAndResultAndSourceById(55L)).thenReturn(Optional.of(job));
        when(jobRepository.findById(55L)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DownloadJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resultRepository.findAllBySearchIdOrderByScoreDescIdAsc(10L)).thenReturn(List.of(failingResult, fallbackResult));
        when(resultRepository.findWithSearchAndSourceById(100L)).thenReturn(Optional.of(failingResult));
        when(resultRepository.findWithSearchAndSourceById(101L)).thenReturn(Optional.of(fallbackResult));
        when(executorRegistry.executorFor(DownloadAcquisitionType.EXTERNAL_STACKS)).thenReturn(failingExecutor);
        when(executorRegistry.executorFor(DownloadAcquisitionType.DIRECT_FILE)).thenReturn(fallbackExecutor);
        when(failingExecutor.download(any(), any())).thenThrow(new DownloadSourceException("Mirror archive.org failed"));
        when(fallbackExecutor.download(any(), any())).thenAnswer(invocation -> {
            DownloadExecutionRequest request = invocation.getArgument(0);
            Path downloaded = request.getTargetPartFile().resolveSibling("fallback.epub");
            Files.writeString(downloaded, "EPUB payload");
            return downloaded;
        });
        when(namingService.buildFinalFileName(any(), eq(DownloadFormat.EPUB))).thenReturn("Fallback candidate.epub");
        doNothing().when(downloadedCbxMetadataService).embedIfApplicable(any(), any(), any());
        when(bookdropDeliveryService.deliver(any(), any(), any(), eq("Fallback candidate.epub")))
                .thenReturn(new BookdropDeliveryService.DeliveryResult(tempDir.resolve("Fallback candidate.epub"), false));

        DownloadJobEntity processed = manager.processQueuedJob(55L);

        assertEquals(DownloadJobStatus.PENDING_REVIEW, processed.getStatus());
        assertEquals(101L, processed.getResult().getId());
        assertEquals(2L, processed.getSource().getId());
        assertEquals(90, processed.getConfidenceScore());
        verify(failingExecutor).download(any(), any());
        verify(fallbackExecutor).download(any(), any());
    }

    @Test
    void processQueuedJob_whenSelectedStacksMirrorFailsFallsBackToNextScoredResult() {
        AppProperties appProperties = new AppProperties();
        appProperties.setBookdropFolder(tempDir.toString());

        DownloadResultRepository resultRepository = mock(DownloadResultRepository.class);
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        DownloadExecutorRegistry executorRegistry = mock(DownloadExecutorRegistry.class);
        DownloadNamingService namingService = mock(DownloadNamingService.class);
        DownloadedCbxMetadataService downloadedCbxMetadataService = mock(DownloadedCbxMetadataService.class);
        BookdropDeliveryService bookdropDeliveryService = mock(BookdropDeliveryService.class);

        DownloadPipelineManager manager = new DownloadPipelineManager(
                appProperties,
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                resultRepository,
                jobRepository,
                mock(DownloadAdapterRegistry.class),
                executorRegistry,
                mock(DownloadScoringService.class),
                namingService,
                mock(DownloadTargetResolver.class),
                downloadedCbxMetadataService,
                bookdropDeliveryService,
                mock(DownloadQueryIntentParser.class),
                mock(DownloadCanonicalResolver.class),
                new ObjectMapper()
        );

        DownloadSourceEntity stacksSource = source(1L, "Stacks", DownloadSourceType.ANNAS_ARCHIVE_API);
        DownloadSourceEntity fallbackSource = source(2L, "Direct", DownloadSourceType.DIRECT_URL);
        DownloadSearchEntity search = DownloadSearchEntity.builder()
                .id(10L)
                .query("michelle obama")
                .contentKind(DownloadContentKind.BOOK)
                .build();
        DownloadResultEntity failingResult = result(100L, search, stacksSource, "Mirror candidate", 90, DownloadAcquisitionType.EXTERNAL_STACKS);
        DownloadResultEntity fallbackResult = result(101L, search, fallbackSource, "Fallback candidate", 90, DownloadAcquisitionType.DIRECT_FILE);
        DownloadJobEntity job = DownloadJobEntity.builder()
                .id(55L)
                .search(search)
                .result(failingResult)
                .source(stacksSource)
                .status(DownloadJobStatus.QUEUED)
                .confidenceScore(90)
                .autoFinalize(false)
                .confidenceThreshold(90)
                .fallbackEnabled(false)
                .build();

        DownloadExecutor failingExecutor = mock(DownloadExecutor.class);
        DownloadExecutor fallbackExecutor = mock(DownloadExecutor.class);

        when(jobRepository.findWithSearchAndResultAndSourceById(55L)).thenReturn(Optional.of(job));
        when(jobRepository.findById(55L)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DownloadJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resultRepository.findAllBySearchIdOrderByScoreDescIdAsc(10L)).thenReturn(List.of(failingResult, fallbackResult));
        when(resultRepository.findWithSearchAndSourceById(100L)).thenReturn(Optional.of(failingResult));
        when(resultRepository.findWithSearchAndSourceById(101L)).thenReturn(Optional.of(fallbackResult));
        when(executorRegistry.executorFor(DownloadAcquisitionType.EXTERNAL_STACKS)).thenReturn(failingExecutor);
        when(executorRegistry.executorFor(DownloadAcquisitionType.DIRECT_FILE)).thenReturn(fallbackExecutor);
        when(failingExecutor.download(any(), any())).thenThrow(new DownloadSourceException("Stacks download failed: Mirror randombook.org failed"));
        when(fallbackExecutor.download(any(), any())).thenAnswer(invocation -> {
            DownloadExecutionRequest request = invocation.getArgument(0);
            Path downloaded = request.getTargetPartFile().resolveSibling("fallback.epub");
            Files.writeString(downloaded, "EPUB payload");
            return downloaded;
        });
        when(namingService.buildFinalFileName(any(), eq(DownloadFormat.EPUB))).thenReturn("Fallback candidate.epub");
        doNothing().when(downloadedCbxMetadataService).embedIfApplicable(any(), any(), any());
        when(bookdropDeliveryService.deliver(any(), any(), any(), eq("Fallback candidate.epub")))
                .thenReturn(new BookdropDeliveryService.DeliveryResult(tempDir.resolve("Fallback candidate.epub"), false));

        DownloadJobEntity processed = manager.processQueuedJob(55L);

        assertEquals(DownloadJobStatus.PENDING_REVIEW, processed.getStatus());
        assertEquals(101L, processed.getResult().getId());
        assertEquals(2L, processed.getSource().getId());
        verify(failingExecutor).download(any(), any());
        verify(fallbackExecutor).download(any(), any());
    }

    @Test
    void processQueuedJob_reloadsFallbackResultBeforeExecutingAttempt() {
        AppProperties appProperties = new AppProperties();
        appProperties.setBookdropFolder(tempDir.toString());

        DownloadResultRepository resultRepository = mock(DownloadResultRepository.class);
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        DownloadExecutorRegistry executorRegistry = mock(DownloadExecutorRegistry.class);
        DownloadNamingService namingService = mock(DownloadNamingService.class);
        DownloadedCbxMetadataService downloadedCbxMetadataService = mock(DownloadedCbxMetadataService.class);
        BookdropDeliveryService bookdropDeliveryService = mock(BookdropDeliveryService.class);

        DownloadPipelineManager manager = new DownloadPipelineManager(
                appProperties,
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                resultRepository,
                jobRepository,
                mock(DownloadAdapterRegistry.class),
                executorRegistry,
                mock(DownloadScoringService.class),
                namingService,
                mock(DownloadTargetResolver.class),
                downloadedCbxMetadataService,
                bookdropDeliveryService,
                mock(DownloadQueryIntentParser.class),
                mock(DownloadCanonicalResolver.class),
                new ObjectMapper()
        );

        DownloadSourceEntity stacksSource = source(1L, "Stacks", DownloadSourceType.ANNAS_ARCHIVE_API);
        DownloadSourceEntity fallbackSource = source(2L, "Direct", DownloadSourceType.DIRECT_URL);
        DownloadSearchEntity search = DownloadSearchEntity.builder()
                .id(10L)
                .query("fallback lazy proxy")
                .contentKind(DownloadContentKind.BOOK)
                .build();
        DownloadResultEntity failingResult = result(100L, search, stacksSource, "Mirror candidate", 100, DownloadAcquisitionType.EXTERNAL_STACKS);
        DownloadResultEntity lazyFallbackProxy = mock(DownloadResultEntity.class);
        when(lazyFallbackProxy.getId()).thenReturn(101L);
        when(lazyFallbackProxy.getScore()).thenReturn(90);
        when(lazyFallbackProxy.getSource()).thenReturn(fallbackSource);
        when(lazyFallbackProxy.getExternalId()).thenThrow(new LazyInitializationException("DownloadResultEntity.externalId"));
        DownloadResultEntity hydratedFallback = result(101L, search, fallbackSource, "Hydrated fallback", 90, DownloadAcquisitionType.DIRECT_FILE);
        DownloadJobEntity job = DownloadJobEntity.builder()
                .id(55L)
                .search(search)
                .result(failingResult)
                .source(stacksSource)
                .status(DownloadJobStatus.QUEUED)
                .confidenceScore(100)
                .autoFinalize(false)
                .confidenceThreshold(90)
                .fallbackEnabled(true)
                .build();

        DownloadExecutor failingExecutor = mock(DownloadExecutor.class);
        DownloadExecutor fallbackExecutor = mock(DownloadExecutor.class);

        when(jobRepository.findWithSearchAndResultAndSourceById(55L)).thenReturn(Optional.of(job));
        when(jobRepository.findById(55L)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DownloadJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(resultRepository.findAllBySearchIdOrderByScoreDescIdAsc(10L)).thenReturn(List.of(failingResult, lazyFallbackProxy));
        when(resultRepository.findWithSearchAndSourceById(100L)).thenReturn(Optional.of(failingResult));
        when(resultRepository.findWithSearchAndSourceById(101L)).thenReturn(Optional.of(hydratedFallback));
        when(executorRegistry.executorFor(DownloadAcquisitionType.EXTERNAL_STACKS)).thenReturn(failingExecutor);
        when(executorRegistry.executorFor(DownloadAcquisitionType.DIRECT_FILE)).thenReturn(fallbackExecutor);
        when(failingExecutor.download(any(), any())).thenThrow(new DownloadSourceException("Stacks download failed: Mirror randombook.org failed"));
        when(fallbackExecutor.download(any(), any())).thenAnswer(invocation -> {
            DownloadExecutionRequest request = invocation.getArgument(0);
            Path downloaded = request.getTargetPartFile().resolveSibling("fallback.epub");
            Files.writeString(downloaded, "EPUB payload");
            return downloaded;
        });
        when(namingService.buildFinalFileName(any(), eq(DownloadFormat.EPUB))).thenReturn("Hydrated fallback.epub");
        doNothing().when(downloadedCbxMetadataService).embedIfApplicable(any(), any(), any());
        when(bookdropDeliveryService.deliver(any(), any(), any(), eq("Hydrated fallback.epub")))
                .thenReturn(new BookdropDeliveryService.DeliveryResult(tempDir.resolve("Hydrated fallback.epub"), false));

        DownloadJobEntity processed = manager.processQueuedJob(55L);

        assertEquals(DownloadJobStatus.PENDING_REVIEW, processed.getStatus());
        assertEquals(101L, processed.getResult().getId());
        assertEquals(2L, processed.getSource().getId());
        verify(fallbackExecutor).download(any(), any());
        verify(resultRepository, atLeastOnce()).findWithSearchAndSourceById(101L);
    }

    @Test
    void retryJob_enablesFallbackForLegacyJobs() {
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        DownloadTargetResolver targetResolver = mock(DownloadTargetResolver.class);

        DownloadPipelineManager manager = new DownloadPipelineManager(
                new AppProperties(),
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                mock(DownloadResultRepository.class),
                jobRepository,
                mock(DownloadAdapterRegistry.class),
                mock(DownloadExecutorRegistry.class),
                mock(DownloadScoringService.class),
                mock(DownloadNamingService.class),
                targetResolver,
                mock(DownloadedCbxMetadataService.class),
                mock(BookdropDeliveryService.class),
                mock(DownloadQueryIntentParser.class),
                mock(DownloadCanonicalResolver.class),
                new ObjectMapper()
        );

        DownloadSourceEntity source = source(1L, "Stacks", DownloadSourceType.ANNAS_ARCHIVE_API);
        DownloadSearchEntity search = DownloadSearchEntity.builder()
                .id(10L)
                .query("legacy failed mirror")
                .contentKind(DownloadContentKind.BOOK)
                .build();
        DownloadResultEntity result = result(100L, search, source, "Legacy candidate", 85, DownloadAcquisitionType.EXTERNAL_STACKS);
        DownloadJobEntity previous = DownloadJobEntity.builder()
                .id(55L)
                .search(search)
                .result(result)
                .source(source)
                .status(DownloadJobStatus.FAILED)
                .confidenceScore(85)
                .autoFinalize(false)
                .confidenceThreshold(90)
                .fallbackEnabled(false)
                .build();

        when(jobRepository.findWithSearchAndResultAndSourceById(55L)).thenReturn(Optional.of(previous));
        when(targetResolver.resolve(null, null, false, DownloadFormat.EPUB)).thenReturn(DownloadTargetResolver.ResolvedTarget.empty());
        when(jobRepository.save(any(DownloadJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DownloadJobEntity retry = manager.retryJob(55L);

        assertEquals(DownloadJobStatus.QUEUED, retry.getStatus());
        assertTrue(retry.getFallbackEnabled(), "Retries must recover legacy failed jobs with fallback enabled");
    }

    @Test
    void queueResult_reusesEquivalentActiveJobInsteadOfSubmittingDuplicate() {
        DownloadResultRepository resultRepository = mock(DownloadResultRepository.class);
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        DownloadTargetResolver targetResolver = mock(DownloadTargetResolver.class);

        DownloadPipelineManager manager = new DownloadPipelineManager(
                new AppProperties(),
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                resultRepository,
                jobRepository,
                mock(DownloadAdapterRegistry.class),
                mock(DownloadExecutorRegistry.class),
                mock(DownloadScoringService.class),
                mock(DownloadNamingService.class),
                targetResolver,
                mock(DownloadedCbxMetadataService.class),
                mock(BookdropDeliveryService.class),
                mock(DownloadQueryIntentParser.class),
                mock(DownloadCanonicalResolver.class),
                new ObjectMapper()
        );

        DownloadSourceEntity source = source(1L, "Stacks", DownloadSourceType.ANNAS_ARCHIVE_API);
        DownloadSearchEntity search = DownloadSearchEntity.builder()
                .id(10L)
                .query("bonne nuit punpun tome 1")
                .contentKind(DownloadContentKind.MANGA)
                .build();
        DownloadResultEntity selectedResult = result(100L, search, source, "Bonne Nuit Punpun Volume 1", 100, DownloadAcquisitionType.EXTERNAL_STACKS);
        selectedResult.setExternalId("bcdd1d9448f4939baa1e7f75fa7b8be5");
        selectedResult.setDetailsUrl("https://annas-archive.test/md5/bcdd1d9448f4939baa1e7f75fa7b8be5");
        DownloadJobEntity existingJob = DownloadJobEntity.builder()
                .id(55L)
                .search(search)
                .result(selectedResult)
                .source(source)
                .status(DownloadJobStatus.DOWNLOADING)
                .confidenceScore(100)
                .build();

        when(resultRepository.findWithSearchAndSourceById(100L)).thenReturn(Optional.of(selectedResult));
        when(jobRepository.findReusableByResultFingerprint(eq(1L), eq("bcdd1d9448f4939baa1e7f75fa7b8be5"), eq(null), eq(null), any()))
                .thenReturn(List.of(existingJob));

        DownloadJobEntity queued = manager.queueResult(100L, null, null, false, 90);

        assertSame(existingJob, queued);
        verify(targetResolver, org.mockito.Mockito.never()).resolve(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void queueResult_doesNotReuseDifferentWebtoonEpisodeWithSameSeriesDetailsUrl() {
        DownloadResultRepository resultRepository = mock(DownloadResultRepository.class);
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        DownloadTargetResolver targetResolver = mock(DownloadTargetResolver.class);

        DownloadPipelineManager manager = new DownloadPipelineManager(
                new AppProperties(),
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                resultRepository,
                jobRepository,
                mock(DownloadAdapterRegistry.class),
                mock(DownloadExecutorRegistry.class),
                mock(DownloadScoringService.class),
                mock(DownloadNamingService.class),
                targetResolver,
                mock(DownloadedCbxMetadataService.class),
                mock(BookdropDeliveryService.class),
                mock(DownloadQueryIntentParser.class),
                mock(DownloadCanonicalResolver.class),
                new ObjectMapper()
        );

        DownloadSourceEntity source = source(4L, "Webtoons", DownloadSourceType.DIRECT_URL);
        DownloadSearchEntity search = DownloadSearchEntity.builder()
                .id(10L)
                .query("surviving the game as a barbarian ep 145")
                .contentKind(DownloadContentKind.WEBTOON)
                .build();
        DownloadResultEntity selectedResult = result(145L, search, source, "S3 Ep 145 Warriors Smile", 100, DownloadAcquisitionType.CLI_GALLERY_DL);
        selectedResult.setContentKind(DownloadContentKind.WEBTOON);
        selectedResult.setFormat(DownloadFormat.CBZ);
        selectedResult.setDetailsUrl("https://www.webtoons.com/en/fantasy/surviving-the-game-as-a-barbarian/list?title_no=5515");
        selectedResult.setDownloadUrl("https://www.webtoons.com/en/fantasy/surviving-the-game-as-a-barbarian/s3-ep-145-warriors-smile/viewer?title_no=5515&episode_no=145");
        DownloadJobEntity episode146Job = DownloadJobEntity.builder()
                .id(31L)
                .search(search)
                .result(selectedResult)
                .source(source)
                .status(DownloadJobStatus.PENDING_REVIEW)
                .confidenceScore(100)
                .build();

        when(resultRepository.findWithSearchAndSourceById(145L)).thenReturn(Optional.of(selectedResult));
        when(jobRepository.findReusableByResultFingerprint(
                eq(4L),
                eq(null),
                eq(null),
                eq("https://www.webtoons.com/en/fantasy/surviving-the-game-as-a-barbarian/s3-ep-145-warriors-smile/viewer?title_no=5515&episode_no=145"),
                any()
        )).thenReturn(List.of());
        when(targetResolver.resolve(null, null, false, DownloadFormat.CBZ)).thenReturn(DownloadTargetResolver.ResolvedTarget.empty());
        when(jobRepository.save(any(DownloadJobEntity.class))).thenAnswer(invocation -> {
            DownloadJobEntity job = invocation.getArgument(0);
            job.setId(99L);
            return job;
        });

        DownloadJobEntity queued = manager.queueResult(145L, null, null, false, 90);

        assertEquals(99L, queued.getId());
        verify(jobRepository).findReusableByResultFingerprint(
                eq(4L),
                eq(null),
                eq(null),
                eq("https://www.webtoons.com/en/fantasy/surviving-the-game-as-a-barbarian/s3-ep-145-warriors-smile/viewer?title_no=5515&episode_no=145"),
                any()
        );
        verify(targetResolver).resolve(null, null, false, DownloadFormat.CBZ);
    }

    private DownloadSourceEntity source(Long id, String name, DownloadSourceType type) {
        return DownloadSourceEntity.builder()
                .id(id)
                .name(name)
                .type(type)
                .enabled(true)
                .build();
    }

    private DownloadResultEntity result(Long id,
                                        DownloadSearchEntity search,
                                        DownloadSourceEntity source,
                                        String title,
                                        int score,
                                        DownloadAcquisitionType acquisitionType) {
        return DownloadResultEntity.builder()
                .id(id)
                .search(search)
                .source(source)
                .title(title)
                .contentKind(DownloadContentKind.BOOK)
                .format(DownloadFormat.EPUB)
                .acquisitionType(acquisitionType)
                .score(score)
                .build();
    }
}
