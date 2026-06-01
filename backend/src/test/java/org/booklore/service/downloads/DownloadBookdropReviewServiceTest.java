package org.booklore.service.downloads;

import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadJobStatus;
import org.booklore.repository.DownloadJobRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadBookdropReviewServiceTest {

    private final DownloadJobRepository repository = mock(DownloadJobRepository.class);
    private final DownloadBookdropReviewService service = new DownloadBookdropReviewService(repository);

    @Test
    void markCompletedForBookdropPath_completesPendingReviewJobsForDeliveredPath() {
        DownloadJobEntity job = DownloadJobEntity.builder()
                .id(42L)
                .status(DownloadJobStatus.PENDING_REVIEW)
                .progressPercent(100)
                .deliveredFilePath("/bookdrop/example.cbz")
                .errorMessage("old")
                .build();
        when(repository.findAllByStatusAndDeliveredFilePath(DownloadJobStatus.PENDING_REVIEW, "/bookdrop/example.cbz"))
                .thenReturn(List.of(job));

        service.markCompletedForBookdropPath("/bookdrop/example.cbz");

        assertEquals(DownloadJobStatus.COMPLETED, job.getStatus());
        assertEquals(100, job.getProgressPercent());
        assertNull(job.getErrorMessage());
        assertNotNull(job.getCompletedAt());
        verify(repository).save(job);
    }

    @Test
    void markCancelledForBookdropPaths_cancelsPendingReviewJobsForDiscardedPath() {
        DownloadJobEntity job = DownloadJobEntity.builder()
                .id(43L)
                .status(DownloadJobStatus.PENDING_REVIEW)
                .progressPercent(100)
                .deliveredFilePath("/bookdrop/discard.cbz")
                .build();
        when(repository.findAllByStatusAndDeliveredFilePath(DownloadJobStatus.PENDING_REVIEW, "/bookdrop/discard.cbz"))
                .thenReturn(List.of(job));

        service.markCancelledForBookdropPaths(List.of("/bookdrop/discard.cbz"));

        assertEquals(DownloadJobStatus.CANCELLED, job.getStatus());
        assertEquals("BookDrop review file was discarded", job.getErrorMessage());
        assertNotNull(job.getCompletedAt());
        verify(repository).save(job);
    }

    @Test
    void markCancelledForBookdropPaths_ignoresEmptyInput() {
        service.markCancelledForBookdropPaths(List.of());

        verify(repository, never()).findAllByStatusAndDeliveredFilePath(DownloadJobStatus.PENDING_REVIEW, "/bookdrop/anything.cbz");
    }
}
