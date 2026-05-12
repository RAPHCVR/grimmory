package org.booklore.service.downloads;

import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserPermissionsEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.downloads.exception.DownloadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Sort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadTargetResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolve_autoFinalizeWithoutConfiguredLibrary_createsDefaultDownloadLibrary() {
        Path defaultPath = tempDir.resolve("Downloads");
        AppProperties appProperties = appProperties(defaultPath);
        LibraryRepository libraryRepository = mock(LibraryRepository.class);
        LibraryPathRepository libraryPathRepository = mock(LibraryPathRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        BookLoreUserEntity admin = adminUser();

        when(libraryRepository.findAll(any(Sort.class))).thenReturn(List.of());
        when(libraryRepository.findByName("Downloads")).thenReturn(Optional.empty());
        when(userRepository.findAll()).thenReturn(List.of(admin));
        when(libraryRepository.saveAndFlush(any(LibraryEntity.class))).thenAnswer(invocation -> {
            LibraryEntity library = invocation.getArgument(0);
            library.setId(42L);
            library.getLibraryPaths().getFirst().setId(84L);
            return library;
        });

        DownloadTargetResolver resolver = new DownloadTargetResolver(appProperties, libraryRepository, libraryPathRepository, userRepository);

        DownloadTargetResolver.ResolvedTarget target = resolver.resolve(null, null, true);

        assertEquals(42L, target.libraryId());
        assertEquals(84L, target.libraryPathId());
        assertTrue(Files.isDirectory(defaultPath));
        verify(libraryRepository).saveAndFlush(any(LibraryEntity.class));
        verify(userRepository).saveAll(any());
        assertEquals(1, admin.getLibraries().size());
        assertTrue(admin.getLibraries().iterator().next().getAllowedFormats().contains(BookFileType.CBX));
    }

    @Test
    void resolve_autoFinalizeWithoutTarget_usesExistingFirstLibraryPath() {
        AppProperties appProperties = appProperties(tempDir.resolve("Downloads"));
        LibraryRepository libraryRepository = mock(LibraryRepository.class);
        LibraryPathRepository libraryPathRepository = mock(LibraryPathRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        LibraryEntity library = library(7L, libraryPath(9L, tempDir.resolve("Existing").toString()));

        when(libraryRepository.findAll(any(Sort.class))).thenReturn(List.of(library));

        DownloadTargetResolver resolver = new DownloadTargetResolver(appProperties, libraryRepository, libraryPathRepository, userRepository);

        DownloadTargetResolver.ResolvedTarget target = resolver.resolve(null, null, true);

        assertEquals(7L, target.libraryId());
        assertEquals(9L, target.libraryPathId());
    }

    @Test
    void resolve_requestedPathMustBelongToRequestedLibrary() {
        AppProperties appProperties = appProperties(tempDir.resolve("Downloads"));
        LibraryRepository libraryRepository = mock(LibraryRepository.class);
        LibraryPathRepository libraryPathRepository = mock(LibraryPathRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        LibraryEntity library = library(7L, libraryPath(9L, tempDir.resolve("Existing").toString()));

        when(libraryRepository.findById(7L)).thenReturn(Optional.of(library));

        DownloadTargetResolver resolver = new DownloadTargetResolver(appProperties, libraryRepository, libraryPathRepository, userRepository);

        assertThrows(DownloadException.class, () -> resolver.resolve(7L, 99L, true));
    }

    private AppProperties appProperties(Path defaultPath) {
        AppProperties appProperties = new AppProperties();
        AppProperties.Downloads downloads = new AppProperties.Downloads();
        downloads.setDefaultLibraryName("Downloads");
        downloads.setDefaultLibraryPath(defaultPath.toString());
        downloads.setAutoCreateLibrary(true);
        appProperties.setDownloads(downloads);
        return appProperties;
    }

    private LibraryEntity library(Long id, LibraryPathEntity path) {
        LibraryEntity library = LibraryEntity.builder()
                .id(id)
                .name("Existing")
                .libraryPaths(new ArrayList<>())
                .build();
        path.setLibrary(library);
        library.getLibraryPaths().add(path);
        return library;
    }

    private LibraryPathEntity libraryPath(Long id, String path) {
        return LibraryPathEntity.builder()
                .id(id)
                .path(path)
                .build();
    }

    private BookLoreUserEntity adminUser() {
        BookLoreUserEntity user = BookLoreUserEntity.builder()
                .id(1L)
                .username("admin")
                .libraries(new HashSet<>())
                .build();
        user.setPermissions(UserPermissionsEntity.builder()
                .permissionAdmin(true)
                .user(user)
                .build());
        return user;
    }
}
