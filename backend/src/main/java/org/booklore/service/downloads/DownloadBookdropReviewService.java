package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadJobStatus;
import org.booklore.repository.DownloadJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadBookdropReviewService {

    private final DownloadJobRepository downloadJobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompletedForBookdropPath(String filePath) {
        markForBookdropPath(filePath, DownloadJobStatus.COMPLETED, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelledForBookdropPaths(Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return;
        }
        for (String filePath : filePaths) {
            markForBookdropPath(filePath, DownloadJobStatus.CANCELLED, "BookDrop review file was discarded");
        }
    }

    private void markForBookdropPath(String filePath, DownloadJobStatus targetStatus, String message) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        var jobs = downloadJobRepository.findAllByStatusAndDeliveredFilePath(DownloadJobStatus.PENDING_REVIEW, filePath);
        if (jobs.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (DownloadJobEntity job : jobs) {
            job.setStatus(targetStatus);
            job.setProgressPercent(100);
            job.setCompletedAt(now);
            job.setErrorMessage(message);
            downloadJobRepository.save(job);
            log.info("Marked download job {} as {} after BookDrop review for {}", job.getId(), targetStatus, filePath);
        }
    }
}
