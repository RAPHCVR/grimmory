package org.booklore.service.downloads;

import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.bookdrop.BookDropService;
import org.booklore.service.bookdrop.BookdropMetadataService;
import org.booklore.service.bookdrop.BookdropMonitoringService;
import org.booklore.service.bookdrop.BookdropNotificationService;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookdropDeliveryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deliver_pendingReview_seedsFetchedMetadataFromDownloadResultBeforeRemoteEnrichment() throws Exception {
        Path stagedFile = tempDir.resolve("one-piece.staged");
        Files.writeString(stagedFile, "cbz");
        Path bookdrop = tempDir.resolve("bookdrop");

        AppProperties appProperties = new AppProperties();
        appProperties.setBookdropFolder(bookdrop.toString());
        BookdropFileRepository repository = mock(BookdropFileRepository.class);
        BookdropMetadataService metadataService = mock(BookdropMetadataService.class);
        BookdropNotificationService notificationService = mock(BookdropNotificationService.class);
        BookdropMonitoringService monitoringService = mock(BookdropMonitoringService.class);
        BookDropService bookDropService = mock(BookDropService.class);

        when(repository.save(any(BookdropFileEntity.class))).thenAnswer(invocation -> {
            BookdropFileEntity entity = invocation.getArgument(0);
            entity.setId(77L);
            return entity;
        });
        doThrow(new RuntimeException("provider offline")).when(metadataService).attachFetchedMetadata(77L);

        BookdropDeliveryService service = new BookdropDeliveryService(
                appProperties,
                monitoringService,
                repository,
                metadataService,
                notificationService,
                bookDropService,
                new ObjectMapper()
        );

        service.deliver(
                DownloadJobEntity.builder().autoFinalize(false).confidenceThreshold(90).build(),
                NormalizedDownloadResult.builder()
                        .title("One Piece")
                        .authors(List.of("Eiichiro Oda"))
                        .seriesName("One Piece")
                        .seriesNumber(100F)
                        .publishedYear(2024)
                        .isbn("9784088837990")
                        .language("en")
                        .format(DownloadFormat.CBZ)
                        .contentKind(DownloadContentKind.MANGA)
                        .detailsUrl("https://example.test/one-piece-100")
                        .build(),
                stagedFile,
                "[ENG] One Piece - Vol. 100.cbz"
        );

        ArgumentCaptor<BookdropFileEntity> captor = ArgumentCaptor.forClass(BookdropFileEntity.class);
        verify(repository).save(captor.capture());
        BookdropFileEntity saved = captor.getValue();
        assertEquals(saved.getOriginalMetadata(), saved.getFetchedMetadata());
        assertTrue(saved.getFetchedMetadata().contains("One Piece"));
        assertTrue(saved.getFetchedMetadata().contains("Eiichiro Oda"));
        assertTrue(Files.exists(bookdrop.resolve("[ENG] One Piece - Vol. 100.cbz")));
        verify(metadataService).attachFetchedMetadata(77L);
        verify(notificationService).sendBookdropFileSummaryNotification();
    }
}
