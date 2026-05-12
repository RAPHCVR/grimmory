package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadJobStatus;
import org.booklore.repository.DownloadJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadJobCleanupService {

    private static final String DOWNLOADS_DIR = ".downloads";
    private static final List<DownloadJobStatus> ACTIVE_STATUSES = List.of(
            DownloadJobStatus.QUEUED,
            DownloadJobStatus.DOWNLOADING,
            DownloadJobStatus.VALIDATING,
            DownloadJobStatus.STAGED,
            DownloadJobStatus.DELIVERING
    );

    private final AppProperties appProperties;
    private final DownloadJobRepository jobRepository;

    @Value("${booklore.downloads.cleanup.stuck-hours:6}")
    private long stuckHours;

    @Scheduled(fixedDelayString = "${booklore.downloads.cleanup.fixed-delay-hours:1}", timeUnit = TimeUnit.HOURS)
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, stuckHours) * 3600L);
        failStuckDownloadingJobs(cutoff);
        cleanupOrphanPartFiles(cutoff);
    }

    private void failStuckDownloadingJobs(Instant cutoff) {
        for (DownloadJobEntity job : jobRepository.findAllByStatusIn(List.of(DownloadJobStatus.DOWNLOADING))) {
            Instant progressMarker = firstNonNull(job.getLastProgressAt(), job.getUpdatedAt(), job.getCreatedAt());
            if (progressMarker != null && progressMarker.isBefore(cutoff)) {
                job.setStatus(DownloadJobStatus.FAILED);
                job.setCompletedAt(Instant.now());
                job.setErrorMessage("Download job timed out after " + stuckHours + " hours without progress");
                jobRepository.save(job);
                log.warn("Marked stuck download job {} as FAILED", job.getId());
            }
        }
    }

    private void cleanupOrphanPartFiles(Instant cutoff) {
        Path downloadsRoot = Path.of(appProperties.getBookdropFolder(), DOWNLOADS_DIR);
        if (Files.notExists(downloadsRoot)) {
            return;
        }
        Set<Path> activePartFiles = activePartFiles();
        try (var stream = Files.walk(downloadsRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".part"))
                    .filter(path -> !activePartFiles.contains(path.toAbsolutePath().normalize()))
                    .filter(path -> olderThan(path, cutoff))
                    .forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("Failed to scan download staging directory {}: {}", downloadsRoot, e.getMessage());
        }
    }

    private Set<Path> activePartFiles() {
        Set<Path> paths = new HashSet<>();
        for (DownloadJobEntity job : jobRepository.findAllByStatusIn(ACTIVE_STATUSES)) {
            if (job.getPartFilePath() != null && !job.getPartFilePath().isBlank()) {
                paths.add(Path.of(job.getPartFilePath()).toAbsolutePath().normalize());
            }
        }
        return paths;
    }

    private boolean olderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
            log.info("Deleted orphan download part file {}", path);
        } catch (IOException e) {
            log.warn("Failed to delete orphan download part file {}: {}", path, e.getMessage());
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
