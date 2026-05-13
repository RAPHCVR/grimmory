package org.booklore.service.book;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.repository.*;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.kobo.CbxConversionService;
import org.booklore.service.kobo.KepubConversionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BookDownloadServiceTest {

    @Mock private BookRepository bookRepository;

    @Mock private BookFileRepository bookFileRepository;

    @Mock private KepubConversionService kepubConversionService;

    @Mock private CbxConversionService cbxConversionService;

    @Mock private AppSettingService appSettingService;

    private BookDownloadService bookDownloadService;

    @TempDir
    private Path tempDir;

    private void setupService() {
        bookDownloadService = new BookDownloadService(
                bookRepository,
                bookFileRepository,
                kepubConversionService,
                cbxConversionService,
                null,
                appSettingService
        );
    }

    @Test
    public void downloadBook_includesContentDispositionAscii() throws Exception {
        setupService();
        String expected = "attachment; filename=\"example.epub\"";

        BookEntity bookEntity = getSampleBook("example.epub");
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));

        Files.createDirectories(bookEntity.getFullFilePath().getParent());
        Files.write(bookEntity.getFullFilePath(), new byte[]{1});

        String actual = bookDownloadService.downloadBook(1L)
                .getHeaders()
                .getFirst("Content-Disposition");

        assertEquals(expected, actual);
    }

    @Test
    public void downloadBook_includesContentDispositionUTF8() throws Exception {
        setupService();
        String expected = "attachment; filename=\"_xample.epub\"; filename*=UTF-8''%C9%87xample.epub";

        BookEntity bookEntity = getSampleBook("ɇxample.epub");
        when(bookRepository.findByIdWithBookFiles(1L)).thenReturn(Optional.of(bookEntity));

        Files.createDirectories(bookEntity.getFullFilePath().getParent());
        Files.write(bookEntity.getFullFilePath(), new byte[]{1});

        String actual = bookDownloadService.downloadBook(1L)
                .getHeaders()
                .getFirst("Content-Disposition");

        assertEquals(expected, actual);
    }

    private BookEntity getSampleBook(String filename) {
        LibraryPathEntity libraryPathEntity = LibraryPathEntity
                .builder()
                .path(tempDir.toString())
                .build();

        BookFileEntity bookFileEntity = BookFileEntity.builder()
                .fileName(filename)
                .fileSubPath("subpath")
                .build();

        BookEntity bookEntity = BookEntity.builder()
                .bookFiles(List.of(bookFileEntity))
                .libraryPath(libraryPathEntity)
                .build();

        // Ensure we've set the identity here.
        bookFileEntity.setBook(bookEntity);

        return bookEntity;
    }
}
