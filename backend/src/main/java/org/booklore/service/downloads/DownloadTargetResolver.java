package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.IconType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.model.enums.MetadataSource;
import org.booklore.repository.LibraryPathRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.downloads.exception.DownloadException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadTargetResolver {

    private static final List<BookFileType> DOWNLOAD_LIBRARY_FORMATS = List.of(
            BookFileType.EPUB,
            BookFileType.PDF,
            BookFileType.CBX,
            BookFileType.FB2,
            BookFileType.MOBI,
            BookFileType.AZW3
    );

    private static final Sort LIBRARY_ORDER = Sort.by(Sort.Order.asc("id"));

    private final AppProperties appProperties;
    private final LibraryRepository libraryRepository;
    private final LibraryPathRepository libraryPathRepository;
    private final UserRepository userRepository;

    @Transactional
    public ResolvedTarget resolve(Long requestedLibraryId, Long requestedLibraryPathId, boolean autoFinalize) {
        return resolve(requestedLibraryId, requestedLibraryPathId, autoFinalize, DownloadFormat.UNKNOWN);
    }

    @Transactional
    public ResolvedTarget resolve(Long requestedLibraryId, Long requestedLibraryPathId, boolean autoFinalize, DownloadFormat requestedFormat) {
        if (requestedLibraryId != null || requestedLibraryPathId != null) {
            return resolveRequestedTarget(requestedLibraryId, requestedLibraryPathId);
        }
        if (!autoFinalize) {
            return ResolvedTarget.empty();
        }
        return findExistingTarget(requestedFormat).orElseGet(this::createDefaultTarget);
    }

    private ResolvedTarget resolveRequestedTarget(Long requestedLibraryId, Long requestedLibraryPathId) {
        if (requestedLibraryId != null) {
            LibraryEntity library = libraryRepository.findById(requestedLibraryId)
                    .orElseThrow(() -> new DownloadException("Target library not found: " + requestedLibraryId));
            LibraryPathEntity path = requestedLibraryPathId == null
                    ? firstPath(library).orElseThrow(() -> new DownloadException("Target library has no configured path: " + requestedLibraryId))
                    : library.getLibraryPaths().stream()
                    .filter(candidate -> requestedLibraryPathId.equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow(() -> new DownloadException("Target library path " + requestedLibraryPathId + " does not belong to library " + requestedLibraryId));
            ensureDirectoryExists(path.getPath());
            return new ResolvedTarget(library.getId(), path.getId());
        }

        LibraryPathEntity path = libraryPathRepository.findById(requestedLibraryPathId)
                .orElseThrow(() -> new DownloadException("Target library path not found: " + requestedLibraryPathId));
        ensureDirectoryExists(path.getPath());
        return new ResolvedTarget(path.getLibrary().getId(), path.getId());
    }

    private Optional<ResolvedTarget> findExistingTarget(DownloadFormat requestedFormat) {
        return libraryRepository.findAll(LIBRARY_ORDER).stream()
                .filter(library -> supportsDownloadFormat(library, requestedFormat))
                .flatMap(library -> firstPath(library).stream().map(path -> {
                    ensureDirectoryExists(path.getPath());
                    return new ResolvedTarget(library.getId(), path.getId());
                }))
                .findFirst();
    }

    private boolean supportsDownloadFormat(LibraryEntity library, DownloadFormat requestedFormat) {
        return toBookFileType(requestedFormat)
                .map(fileType -> {
                    List<BookFileType> allowedFormats = library.getAllowedFormats();
                    return allowedFormats == null || allowedFormats.isEmpty() || allowedFormats.contains(fileType);
                })
                .orElse(true);
    }

    private Optional<BookFileType> toBookFileType(DownloadFormat format) {
        if (format == null || format == DownloadFormat.UNKNOWN || format.extension().isBlank()) {
            return Optional.empty();
        }
        return BookFileType.fromExtension(format.extension());
    }

    private Optional<LibraryPathEntity> firstPath(LibraryEntity library) {
        return Optional.ofNullable(library.getLibraryPaths())
                .orElse(List.of())
                .stream()
                .filter(path -> path.getPath() != null && !path.getPath().isBlank())
                .min(Comparator.comparing(LibraryPathEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LibraryPathEntity::getPath));
    }

    private ResolvedTarget createDefaultTarget() {
        AppProperties.Downloads downloads = appProperties.getDownloads();
        if (downloads == null || !downloads.isAutoCreateLibrary()) {
            throw new DownloadException("Auto-finalize requires a target library, but none is configured");
        }

        String libraryName = normalizeDefaultLibraryName(downloads.getDefaultLibraryName());
        String libraryPath = normalizeDefaultLibraryPath(downloads.getDefaultLibraryPath());
        ensureDirectoryExists(libraryPath);

        LibraryEntity library = libraryRepository.findByName(libraryName)
                .orElseGet(() -> LibraryEntity.builder()
                        .name(libraryName)
                        .icon("pi pi-download")
                        .iconType(IconType.PRIME_NG)
                        .watch(true)
                        .libraryPaths(new ArrayList<>())
                        .bookEntities(new ArrayList<>())
                        .users(new HashSet<>())
                        .formatPriority(new ArrayList<>(List.of(BookFileType.EPUB, BookFileType.CBX, BookFileType.PDF, BookFileType.AZW3, BookFileType.MOBI, BookFileType.FB2)))
                        .allowedFormats(new ArrayList<>(DOWNLOAD_LIBRARY_FORMATS))
                        .organizationMode(LibraryOrganizationMode.BOOK_PER_FILE)
                        .metadataSource(MetadataSource.EMBEDDED)
                        .build());
        if (library.getLibraryPaths() == null) {
            library.setLibraryPaths(new ArrayList<>());
        }
        library.setAllowedFormats(ensureDownloadFormatsAllowed(library.getAllowedFormats()));

        LibraryPathEntity path = firstPath(library)
                .orElseGet(() -> {
                    LibraryPathEntity createdPath = LibraryPathEntity.builder()
                            .path(libraryPath)
                            .library(library)
                            .build();
                    library.getLibraryPaths().add(createdPath);
                    return createdPath;
                });

        LibraryEntity saved = libraryRepository.saveAndFlush(library);
        assignDefaultLibraryToAdminUsers(saved);

        LibraryPathEntity savedPath = firstPath(saved)
                .orElseThrow(() -> new DownloadException("Default download library was created without a path"));
        log.info("Resolved downloads default target to library '{}' ({}) path '{}'", saved.getName(), saved.getId(), savedPath.getPath());
        return new ResolvedTarget(saved.getId(), savedPath.getId() == null ? path.getId() : savedPath.getId());
    }

    private List<BookFileType> ensureDownloadFormatsAllowed(List<BookFileType> currentAllowedFormats) {
        if (currentAllowedFormats == null || currentAllowedFormats.isEmpty()) {
            return new ArrayList<>(DOWNLOAD_LIBRARY_FORMATS);
        }
        List<BookFileType> merged = new ArrayList<>(currentAllowedFormats);
        DOWNLOAD_LIBRARY_FORMATS.stream()
                .filter(format -> !merged.contains(format))
                .forEach(merged::add);
        return merged;
    }

    private void assignDefaultLibraryToAdminUsers(LibraryEntity library) {
        List<BookLoreUserEntity> admins = userRepository.findAll().stream()
                .filter(user -> user.getPermissions() != null && user.getPermissions().isPermissionAdmin())
                .filter(user -> Optional.ofNullable(user.getLibraries()).orElseGet(Set::of).stream()
                        .noneMatch(existing -> existing.getId().equals(library.getId())))
                .toList();
        if (admins.isEmpty()) {
            return;
        }
        admins.forEach(user -> {
            Set<LibraryEntity> libraries = new HashSet<>(Optional.ofNullable(user.getLibraries()).orElseGet(Set::of));
            libraries.add(library);
            user.setLibraries(libraries);
        });
        userRepository.saveAll(admins);
    }

    private String normalizeDefaultLibraryName(String configuredName) {
        if (configuredName == null || configuredName.isBlank()) {
            return "Downloads";
        }
        return configuredName.trim();
    }

    private String normalizeDefaultLibraryPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return "/books/Downloads";
        }
        return Path.of(configuredPath.trim()).toAbsolutePath().normalize().toString();
    }

    private void ensureDirectoryExists(String path) {
        try {
            Files.createDirectories(Path.of(path));
        } catch (IOException e) {
            throw new DownloadException("Could not create target library path: " + path, e);
        }
    }

    public record ResolvedTarget(Long libraryId, Long libraryPathId) {
        static ResolvedTarget empty() {
            return new ResolvedTarget(null, null);
        }
    }
}
