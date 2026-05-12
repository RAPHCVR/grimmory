package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.BookdropFinalizeRequest;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.service.bookdrop.BookDropService;
import org.booklore.service.bookdrop.BookdropMetadataService;
import org.booklore.service.bookdrop.BookdropMonitoringService;
import org.booklore.service.bookdrop.BookdropNotificationService;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookdropDeliveryService {

    private final AppProperties appProperties;
    private final BookdropMonitoringService bookdropMonitoringService;
    private final BookdropFileRepository bookdropFileRepository;
    private final BookdropMetadataService bookdropMetadataService;
    private final BookdropNotificationService bookdropNotificationService;
    private final BookDropService bookDropService;
    private final ObjectMapper objectMapper;

    public DeliveryResult deliver(DownloadJobEntity job, NormalizedDownloadResult result, Path stagedFile, String finalFileName) {
        Path bookdropRoot = Path.of(appProperties.getBookdropFolder());
        Path finalPath = bookdropRoot.resolve(finalFileName);
        boolean autoFinalize = shouldAutoFinalize(job);

        try {
            Files.createDirectories(bookdropRoot);
            if (Files.exists(finalPath)) {
                throw new DownloadException("BookDrop target already exists: " + finalPath);
            }

            bookdropMonitoringService.pauseMonitoring();
            moveAtomically(stagedFile, finalPath);

            BookMetadata downloadMetadata = toBookMetadata(result);
            String downloadMetadataJson = objectMapper.writeValueAsString(downloadMetadata);
            BookdropFileEntity bookdropFile = bookdropFileRepository.save(BookdropFileEntity.builder()
                    .filePath(finalPath.toString())
                    .fileName(finalFileName)
                    .fileSize(Files.size(finalPath))
                    .status(BookdropFileEntity.Status.PENDING_REVIEW)
                    .originalMetadata(downloadMetadataJson)
                    .fetchedMetadata(downloadMetadataJson)
                    .build());

            if (autoFinalize) {
                finalizeBookdropFile(bookdropFile, job, result);
                return new DeliveryResult(finalPath, true);
            }

            attachBookdropMetadata(bookdropFile.getId());
            bookdropNotificationService.sendBookdropFileSummaryNotification();
            return new DeliveryResult(finalPath, false);
        } catch (Exception e) {
            throw new DownloadException("BookDrop delivery failed: " + e.getMessage(), e);
        } finally {
            bookdropMonitoringService.resumeMonitoring();
        }
    }

    private void finalizeBookdropFile(BookdropFileEntity bookdropFile, DownloadJobEntity job, NormalizedDownloadResult result) {
        BookdropFinalizeRequest.BookdropFinalizeFile file = new BookdropFinalizeRequest.BookdropFinalizeFile();
        file.setFileId(bookdropFile.getId());
        file.setLibraryId(job.getTargetLibraryId());
        file.setPathId(job.getTargetLibraryPathId());
        file.setMetadata(toBookMetadata(result));

        BookdropFinalizeRequest request = new BookdropFinalizeRequest();
        request.setSelectAll(false);
        request.setFiles(List.of(file));
        request.setDefaultLibraryId(job.getTargetLibraryId());
        request.setDefaultPathId(job.getTargetLibraryPathId());
        bookDropService.finalizeImport(request);
    }

    private void attachBookdropMetadata(Long bookdropFileId) {
        try {
            bookdropMetadataService.attachFetchedMetadata(bookdropFileId);
        } catch (Exception e) {
            log.warn("BookDrop metadata enrichment failed for downloaded file {}: {}", bookdropFileId, e.getMessage());
        }
    }

    private boolean shouldAutoFinalize(DownloadJobEntity job) {
        return Boolean.TRUE.equals(job.getAutoFinalize())
                && job.getConfidenceScore() != null
                && job.getConfidenceScore() >= job.getConfidenceThreshold()
                && job.getTargetLibraryId() != null
                && job.getTargetLibraryPathId() != null;
    }

    private BookMetadata toBookMetadata(NormalizedDownloadResult result) {
        LocalDate publishedDate = result.getPublishedYear() == null
                ? null
                : LocalDate.of(result.getPublishedYear(), 1, 1);
        return BookMetadata.builder()
                .title(result.getTitle())
                .authors(result.getAuthors())
                .seriesName(result.getSeriesName())
                .seriesNumber(result.getSeriesNumber())
                .publishedDate(publishedDate)
                .isbn13(normalizeIsbn13(result.getIsbn()))
                .isbn10(normalizeIsbn10(result.getIsbn()))
                .language(result.getLanguage())
                .externalUrl(result.getDetailsUrl())
                .build();
    }

    private String normalizeIsbn13(String isbn) {
        String clean = cleanIsbn(isbn);
        return clean.length() == 13 ? clean : null;
    }

    private String normalizeIsbn10(String isbn) {
        String clean = cleanIsbn(isbn);
        return clean.length() == 10 ? clean : null;
    }

    private String cleanIsbn(String isbn) {
        return isbn == null ? "" : isbn.replaceAll("[^0-9Xx]", "").toUpperCase();
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    public record DeliveryResult(Path finalPath, boolean autoFinalized) {
    }
}
