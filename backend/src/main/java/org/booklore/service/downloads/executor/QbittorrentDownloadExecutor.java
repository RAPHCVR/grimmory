package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.BookFileExtension;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
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
                    Path downloadedFile = copyCompletedPayload(lastInfo, localSavePath, request);
                    if (progressSink != null) {
                        progressSink.onProgress(100);
                    }
                    cleanupTorrentTask(config, lastInfo.hash(), localSavePath, request.getStagingDir());
                    return downloadedFile;
                }
            }
            sleep(config.pollIntervalSeconds());
        }
        String state = lastInfo == null ? "not visible in qBittorrent" : lastInfo.state() + " at " + Math.round(lastInfo.progress() * 100D) + "%";
        throw new DownloadSourceException("Torrent did not complete before timeout: " + state);
    }

    private Path copyCompletedPayload(QbittorrentClient.TorrentInfo info, Path localSavePath, DownloadExecutionRequest request) {
        try {
            Path payload = resolvePayloadPath(info, localSavePath, request.getResult().getContentKind());
            Path targetFile = targetFileForPayload(request.getTargetPartFile(), payload, request.getResult().getContentKind());
            Files.createDirectories(targetFile.getParent());
            Files.copy(payload, targetFile, StandardCopyOption.REPLACE_EXISTING);
            return targetFile;
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to copy completed torrent payload into staging: " + e.getMessage(), e);
        }
    }

    private void saveExternalTask(DownloadJobEntity job, String externalId) {
        job.setExternalTaskId(externalId);
        job.setExternalTaskType(EXTERNAL_TASK_TYPE);
        jobRepository.save(job);
    }

    private void cleanupTorrentTask(QbittorrentClient.QbittorrentConfig config, String hash, Path localSavePath, Path stagingDir) {
        if (!config.deleteTorrentOnComplete()) {
            return;
        }
        boolean deleteFiles = config.deleteFilesOnComplete() && isSameOrChild(localSavePath, stagingDir);
        qbittorrentClient.deleteTorrent(config, hash, deleteFiles);
    }

    private boolean isSameOrChild(Path candidate, Path parent) {
        if (candidate == null || parent == null) {
            return false;
        }
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        Path normalizedParent = parent.toAbsolutePath().normalize();
        return normalizedCandidate.equals(normalizedParent) || normalizedCandidate.startsWith(normalizedParent);
    }

    private Path resolvePayloadPath(QbittorrentClient.TorrentInfo info, Path localSavePath, DownloadContentKind contentKind) throws IOException {
        Path contentPath = info.contentPath() == null || info.contentPath().isBlank() ? null : Path.of(info.contentPath());
        if (contentPath != null && Files.exists(contentPath)) {
            return supportedPayload(contentPath, contentKind);
        }

        Path fallback = info.name() == null || info.name().isBlank() ? localSavePath : localSavePath.resolve(info.name());
        if (Files.exists(fallback)) {
            return supportedPayload(fallback, contentKind);
        }

        if (Files.exists(localSavePath)) {
            return supportedPayload(localSavePath, contentKind);
        }
        throw new DownloadSourceException("Completed torrent payload is not visible from BookLore at " + localSavePath);
    }

    private Path supportedPayload(Path path, DownloadContentKind contentKind) throws IOException {
        if (Files.isRegularFile(path)) {
            if (isSupportedPayload(path, contentKind)) {
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
                    .filter(candidate -> isSupportedPayload(candidate, contentKind))
                    .max(Comparator.comparingLong(this::safeSize))
                    .orElseThrow(() -> new DownloadSourceException("Torrent completed without a supported BookLore file inside " + path));
        }
    }

    private Path targetFileForPayload(Path targetPartFile, Path payload, DownloadContentKind contentKind) throws IOException {
        String extension = BookFileExtension.fromFileName(payload.getFileName().toString())
                .map(BookFileExtension::getExtension)
                .orElseGet(() -> isComicZipPayload(payload, contentKind) ? "cbz" : null);
        if (extension == null || extension.isBlank()) {
            return targetPartFile;
        }
        String baseName = targetPartFile.getFileName().toString();
        if (baseName.endsWith(".part")) {
            baseName = baseName.substring(0, baseName.length() - ".part".length());
        }
        return targetPartFile.getParent().resolve(baseName + "." + extension);
    }

    private boolean isSupportedPayload(Path path, DownloadContentKind contentKind) {
        String fileName = path.getFileName().toString();
        return BookFileExtension.fromFileName(fileName).isPresent() || isComicZipPayload(path, contentKind);
    }

    private boolean isComicZipPayload(Path path, DownloadContentKind contentKind) {
        if (!isSequentialArt(contentKind) || !path.getFileName().toString().toLowerCase().endsWith(".zip")) {
            return false;
        }
        try {
            byte[] signature = new byte[4];
            int read;
            try (var in = Files.newInputStream(path)) {
                read = in.read(signature);
            }
            return signature.length >= 4
                    && read == 4
                    && signature[0] == 'P'
                    && signature[1] == 'K'
                    && (signature[2] == 3 || signature[2] == 5 || signature[2] == 7)
                    && (signature[3] == 4 || signature[3] == 6 || signature[3] == 8);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isSequentialArt(DownloadContentKind contentKind) {
        return contentKind != null && contentKind.isSequentialArt();
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
