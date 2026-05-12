package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadJobStatus;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.service.downloads.exception.DownloadException;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadJobRunner {

    private static final Set<DownloadJobStatus> ACTIVE_STATUSES = EnumSet.of(
            DownloadJobStatus.DOWNLOADING,
            DownloadJobStatus.VALIDATING,
            DownloadJobStatus.STAGED,
            DownloadJobStatus.DELIVERING,
            DownloadJobStatus.AUTO_FINALIZING
    );

    private final DownloadJobRepository jobRepository;
    private final DownloadPipelineManager pipelineManager;
    private final AsyncTaskExecutor taskExecutor;
    private final Set<Long> runningJobIds = ConcurrentHashMap.newKeySet();

    public DownloadJobEntity start(Long jobId) {
        DownloadJobEntity job = jobRepository.findWithSearchAndResultAndSourceById(jobId)
                .orElseThrow(() -> new DownloadException("Download job not found: " + jobId));

        if (ACTIVE_STATUSES.contains(job.getStatus()) || runningJobIds.contains(jobId)) {
            return job;
        }
        if (job.getStatus() != DownloadJobStatus.QUEUED) {
            throw new DownloadException("Only queued download jobs can be started. Job " + jobId + " is " + job.getStatus());
        }
        if (!runningJobIds.add(jobId)) {
            return job;
        }

        try {
            taskExecutor.execute(() -> runJob(jobId));
        } catch (RejectedExecutionException e) {
            runningJobIds.remove(jobId);
            throw new DownloadException("Download job runner rejected job " + jobId, e);
        }
        return job;
    }

    private void runJob(Long jobId) {
        try {
            pipelineManager.processQueuedJob(jobId);
        } catch (Exception e) {
            log.error("Unexpected failure while running download job {}: {}", jobId, e.getMessage(), e);
        } finally {
            runningJobIds.remove(jobId);
        }
    }
}
