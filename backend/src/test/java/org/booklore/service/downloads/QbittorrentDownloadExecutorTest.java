package org.booklore.service.downloads;

import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.service.downloads.client.QbittorrentClient;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.QbittorrentDownloadExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QbittorrentDownloadExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void download_completedMangaZipPayload_isCopiedAsCbz() throws Exception {
        Path payload = createZipPayload();
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        QbittorrentClient qbittorrentClient = configuredClient(payload, "payload.zip");
        QbittorrentDownloadExecutor executor = new QbittorrentDownloadExecutor(qbittorrentClient, jobRepository);
        Path targetPartFile = tempDir.resolve("staging").resolve("download.part");

        Path downloaded = executor.download(request(targetPartFile, DownloadContentKind.MANGA), ignored -> {});

        assertEquals("download.cbz", downloaded.getFileName().toString());
        assertTrue(Files.exists(downloaded));
        assertArrayEquals(Files.readAllBytes(payload), Files.readAllBytes(downloaded));
        verify(jobRepository).save(org.mockito.ArgumentMatchers.any(DownloadJobEntity.class));
    }

    @Test
    void download_completedMangaImageDirectory_isPackagedAsCbz() throws Exception {
        Path payload = createImageDirectoryPayload();
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        QbittorrentClient qbittorrentClient = configuredClient(payload, "payload-dir");
        QbittorrentDownloadExecutor executor = new QbittorrentDownloadExecutor(qbittorrentClient, jobRepository);
        Path targetPartFile = tempDir.resolve("staging").resolve("download.part");

        Path downloaded = executor.download(request(targetPartFile, DownloadContentKind.MANGA), ignored -> {});

        assertEquals("download.cbz", downloaded.getFileName().toString());
        assertTrue(Files.exists(downloaded));
        try (ZipFile cbz = new ZipFile(downloaded.toFile())) {
            assertNotNull(cbz.getEntry("01 Cover/000.jpg"));
            assertNotNull(cbz.getEntry("02 Ch101/001.jpg"));
            assertNotNull(cbz.getEntry("02 Ch101/002.png"));
        }
        verify(jobRepository).save(org.mockito.ArgumentMatchers.any(DownloadJobEntity.class));
    }

    @Test
    void download_retryWithExistingExternalTask_reusesTorrentWithoutSubmittingDuplicate() throws Exception {
        Path payload = createImageDirectoryPayload();
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        QbittorrentClient qbittorrentClient = configuredClient(payload, "payload-dir");
        QbittorrentClient.TorrentInfo existing = new QbittorrentClient.TorrentInfo(
                "hash",
                "payload-dir",
                "uploading",
                1.0D,
                payload.toString(),
                tempDir.toString());
        when(qbittorrentClient.findTorrent(
                org.mockito.ArgumentMatchers.any(QbittorrentClient.QbittorrentConfig.class),
                isNull(),
                eq("booklore-job-6"))).thenReturn(Optional.of(existing));
        when(qbittorrentClient.findTorrent(
                org.mockito.ArgumentMatchers.any(QbittorrentClient.QbittorrentConfig.class),
                eq("hash"),
                eq("booklore-job-6"))).thenReturn(Optional.of(existing));
        QbittorrentDownloadExecutor executor = new QbittorrentDownloadExecutor(qbittorrentClient, jobRepository);
        DownloadJobEntity retryJob = DownloadJobEntity.builder()
                .id(43L)
                .externalTaskType("QBITTORRENT")
                .externalTaskId("booklore-job-6")
                .build();

        Path downloaded = executor.download(
                request(tempDir.resolve("staging").resolve("download.part"), DownloadContentKind.MANGA, retryJob),
                ignored -> {});

        assertEquals("download.cbz", downloaded.getFileName().toString());
        verify(qbittorrentClient, never()).addUrl(
                org.mockito.ArgumentMatchers.any(QbittorrentClient.QbittorrentConfig.class),
                anyString(),
                anyString(),
                anyString());
        verify(jobRepository).save(org.mockito.ArgumentMatchers.argThat(job -> "hash".equals(job.getExternalTaskId())));
    }

    @Test
    void download_completedBookZipPayload_isRejected() throws Exception {
        Path payload = createZipPayload();
        QbittorrentDownloadExecutor executor = new QbittorrentDownloadExecutor(
                configuredClient(payload, "payload.zip"),
                mock(DownloadJobRepository.class));

        DownloadSourceException error = assertThrows(DownloadSourceException.class,
                () -> executor.download(request(tempDir.resolve("download.part"), DownloadContentKind.BOOK), ignored -> {}));

        assertTrue(error.getMessage().contains("unsupported file type"));
    }

    @Test
    void download_whenPayloadIsInBookLoreStaging_deletesTorrentFilesAfterCopy() throws Exception {
        Path payload = createZipPayload();
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        QbittorrentClient qbittorrentClient = configuredClient(payload, "payload.zip", true, true, tempDir.toString());
        QbittorrentDownloadExecutor executor = new QbittorrentDownloadExecutor(qbittorrentClient, jobRepository);

        executor.download(request(tempDir.resolve("download.part"), DownloadContentKind.MANGA), ignored -> {});

        verify(qbittorrentClient).deleteTorrent(
                org.mockito.ArgumentMatchers.any(QbittorrentClient.QbittorrentConfig.class),
                org.mockito.ArgumentMatchers.eq("hash"),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void download_whenPayloadIsOutsideBookLoreStaging_keepsExternalFiles() throws Exception {
        Path payload = createZipPayload();
        Path externalPath = tempDir.resolve("external-client-downloads");
        DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
        QbittorrentClient qbittorrentClient = configuredClient(payload, "payload.zip", true, true, externalPath.toString());
        QbittorrentDownloadExecutor executor = new QbittorrentDownloadExecutor(qbittorrentClient, jobRepository);

        executor.download(request(tempDir.resolve("staging").resolve("download.part"), DownloadContentKind.MANGA), ignored -> {});

        verify(qbittorrentClient).deleteTorrent(
                org.mockito.ArgumentMatchers.any(QbittorrentClient.QbittorrentConfig.class),
                org.mockito.ArgumentMatchers.eq("hash"),
                org.mockito.ArgumentMatchers.eq(false));
    }

    private QbittorrentClient configuredClient(Path payload, String torrentName) {
        return configuredClient(payload, torrentName, false, false, tempDir.toString());
    }

    private QbittorrentClient configuredClient(Path payload,
                                               String torrentName,
                                               boolean deleteTorrentOnComplete,
                                               boolean deleteFilesOnComplete,
                                               String localSavePath) {
        QbittorrentClient client = mock(QbittorrentClient.class);
        QbittorrentClient.QbittorrentConfig config = new QbittorrentClient.QbittorrentConfig(
                "http://127.0.0.1:8080",
                "admin",
                "adminadmin",
                null,
                null,
                tempDir.toString(),
                localSavePath,
                1,
                1,
                deleteTorrentOnComplete,
                deleteFilesOnComplete);
        when(client.readConfig(org.mockito.ArgumentMatchers.any())).thenReturn(config);
        when(client.addUrl(org.mockito.ArgumentMatchers.eq(config), anyString(), anyString(), anyString()))
                .thenReturn(new QbittorrentClient.SubmittedTorrent("hash", "booklore-job-42"));
        when(client.findTorrent(config, "hash", "booklore-job-42"))
                .thenReturn(Optional.of(new QbittorrentClient.TorrentInfo(
                        "hash",
                        torrentName,
                        "uploading",
                        1.0D,
                        payload.toString(),
                        tempDir.toString())));
        return client;
    }

    private DownloadExecutionRequest request(Path targetPartFile, DownloadContentKind contentKind) {
        return request(targetPartFile, contentKind, DownloadJobEntity.builder().id(42L).build());
    }

    private DownloadExecutionRequest request(Path targetPartFile, DownloadContentKind contentKind, DownloadJobEntity job) {
        return DownloadExecutionRequest.builder()
                .job(job)
                .source(DownloadSourceEntity.builder().name("Prowlarr").build())
                .result(NormalizedDownloadResult.builder()
                        .downloadUrl("magnet:?xt=urn:btih:hash")
                        .contentKind(contentKind)
                        .acquisitionType(DownloadAcquisitionType.TORRENT)
                        .build())
                .stagingDir(targetPartFile.getParent() == null ? tempDir : targetPartFile.getParent())
                .targetPartFile(targetPartFile)
                .build();
    }

    private Path createZipPayload() throws Exception {
        Path payload = tempDir.resolve("payload.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(payload))) {
            zip.putNextEntry(new ZipEntry("001.jpg"));
            zip.write(new byte[]{1, 2, 3, 4});
            zip.closeEntry();
        }
        return payload;
    }

    private Path createImageDirectoryPayload() throws Exception {
        Path payload = tempDir.resolve("payload-dir");
        Path coverDir = payload.resolve("01 Cover");
        Path chapterDir = payload.resolve("02 Ch101");
        Files.createDirectories(coverDir);
        Files.createDirectories(chapterDir);
        Files.write(coverDir.resolve("000.jpg"), new byte[]{1});
        Files.write(chapterDir.resolve("001.jpg"), new byte[]{2});
        Files.write(chapterDir.resolve("002.png"), new byte[]{3});
        Files.write(chapterDir.resolve("readme.txt"), new byte[]{4});
        return payload;
    }
}
