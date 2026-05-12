package org.booklore.service.downloads;

import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadJobStatus;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.service.downloads.exception.DownloadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadJobRunnerTest {

    @Mock
    private DownloadJobRepository jobRepository;

    @Mock
    private DownloadPipelineManager pipelineManager;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    private DownloadJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new DownloadJobRunner(jobRepository, pipelineManager, taskExecutor);
    }

    @Test
    void start_queuedJob_submitsBackgroundProcessing() {
        DownloadJobEntity job = job(42L, DownloadJobStatus.QUEUED);
        when(jobRepository.findWithSearchAndResultAndSourceById(42L)).thenReturn(Optional.of(job));

        DownloadJobEntity returned = runner.start(42L);

        assertSame(job, returned);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(runnableCaptor.capture());

        runnableCaptor.getValue().run();

        verify(pipelineManager).processQueuedJob(42L);
    }

    @Test
    void start_activeJob_doesNotSubmitDuplicateWork() {
        DownloadJobEntity job = job(42L, DownloadJobStatus.DOWNLOADING);
        when(jobRepository.findWithSearchAndResultAndSourceById(42L)).thenReturn(Optional.of(job));

        DownloadJobEntity returned = runner.start(42L);

        assertSame(job, returned);
        verify(taskExecutor, never()).execute(any(Runnable.class));
        verify(pipelineManager, never()).processQueuedJob(anyLong());
    }

    @Test
    void start_finishedJob_throwsInsteadOfReprocessing() {
        DownloadJobEntity job = job(42L, DownloadJobStatus.COMPLETED);
        when(jobRepository.findWithSearchAndResultAndSourceById(42L)).thenReturn(Optional.of(job));

        assertThrows(DownloadException.class, () -> runner.start(42L));

        verify(taskExecutor, never()).execute(any(Runnable.class));
        verify(pipelineManager, never()).processQueuedJob(anyLong());
    }

    private DownloadJobEntity job(Long id, DownloadJobStatus status) {
        return DownloadJobEntity.builder()
                .id(id)
                .status(status)
                .build();
    }
}
