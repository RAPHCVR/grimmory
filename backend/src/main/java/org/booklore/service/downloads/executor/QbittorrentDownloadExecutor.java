package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.service.downloads.client.QbittorrentClient;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class QbittorrentDownloadExecutor implements DownloadExecutor {

    private static final String EXTERNAL_TASK_TYPE = "QBITTORRENT";

    private final QbittorrentClient qbittorrentClient;
    private final DownloadJobRepository jobRepository;

    @Override
    public boolean supports(DownloadAcquisitionType acquisitionType) {
        return acquisitionType == DownloadAcquisitionType.TORRENT;
    }

    @Override
    public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
        String url = request.getResult().getDownloadUrl();
        if (url == null || url.isBlank()) {
            throw new DownloadSourceException("Torrent result does not expose a magnet or torrent URL");
        }

        QbittorrentClient.QbittorrentConfig config = qbittorrentClient.readConfig(request.getSource());
        Long jobId = request.getJob().getId();
        String jobTag = "booklore-job-" + jobId;
        String remoteSavePath = config.resolveRemoteSavePath(request.getStagingDir(), jobId);
        Path localSavePath = config.resolveLocalSavePath(request.getStagingDir(), jobId);

        QbittorrentClient.SubmittedTorrent submitted = qbittorrentClient.addUrl(config, url, remoteSavePath, jobTag);
        String externalId = submitted.hash() == null || submitted.hash().isBlank() ? submitted.jobTag() : submitted.hash();
        saveExternalTask(request.getJob(), externalId);

        Instant deadline = Instant.now().plus(Duration.ofMinutes(config.timeoutMinutes()));
        QbittorrentClient.TorrentInfo lastInfo = null;
        while (Instant.now().isBefore(deadline)) {
            Optional<QbittorrentClient.TorrentInfo> torrent = qbittorrentClient.findTorrent(config, submitted.hash(), submitted.jobTag());
            if (torrent.isPresent()) {
                lastInfo = torrent.get();
                int percent = (int) Math.min(99, Math.floor(lastInfo.progress() * 100D));
                if (progressSink != null) {
                    progressSink.onProgress(percent);
                }
                if (lastInfo.complete()) {
                    copyCompletedPayload(lastInfo, localSavePath, request.getTargetPartFile());
                    if (progressSink != null) {
                        progressSink.onProgress(100);
                    }
                    if (config.deleteTorrentOnComplete()) {
                        qbittorrentClient.deleteTorrent(config, lastInfo.hash(), false);
                    }
                    return request.getTargetPartFile();
                }
            }
            sleep(config.pollIntervalSeconds());
        }
        String state = lastInfo == null ? "not visible in qBittorrent" : lastInfo.state() + " at " + Math.round(lastInfo.progress() * 100D) + "%";
        throw new DownloadSourceException("Torrent did not complete before timeout: " + state);
    }

    private void copyCompletedPayload(QbittorrentClient.TorrentInfo info, Path localSavePath, Path targetPartFile) {
        try {
            Path payload = resolvePayloadPath(info, localSavePath);
            Files.copy(payload, targetPartFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to copy completed torrent payload into staging: " + e.getMessage(), e);
        }
    }

    private void saveExternalTask(DownloadJobEntity job, String externalId) {
        job.setExternalTaskId(externalId);
        job.setExternalTaskType(EXTERNAL_TASK_TYPE);
        jobRepository.save(job);
    }

    private Path resolvePayloadPath(QbittorrentClient.TorrentInfo info, Path localSavePath) throws IOException {
        Path contentPath = info.contentPath() == null || info.contentPath().isBlank() ? null : Path.of(info.contentPath());
        if (contentPath != null && Files.exists(contentPath)) {
            return supportedPayload(contentPath);
        }

        Path fallback = info.name() == null || info.name().isBlank() ? localSavePath : localSavePath.resolve(info.name());
        if (Files.exists(fallback)) {
            return supportedPayload(fallback);
        }

        if (Files.exists(localSavePath)) {
            return supportedPayload(localSavePath);
        }
        throw new DownloadSourceException("Completed torrent payload is not visible from BookLore at " + localSavePath);
    }

    private Path supportedPayload(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            if (BookFileExtension.fromFileName(path.getFileName().toString()).isPresent()) {
                return path;
            }
            throw new DownloadSourceException("Torrent completed with unsupported file type: " + path.getFileName());
        }
        if (!Files.isDirectory(path)) {
            throw new DownloadSourceException("Torrent completed payload is not a regular file or directory: " + path);
        }
        try (var stream = Files.walk(path)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(candidate -> BookFileExtension.fromFileName(candidate.getFileName().toString()).isPresent())
                    .max(Comparator.comparingLong(this::safeSize))
                    .orElseThrow(() -> new DownloadSourceException("Torrent completed without a supported BookLore file inside " + path));
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Torrent monitoring interrupted", e);
        }
    }
}
