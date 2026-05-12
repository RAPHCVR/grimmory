package org.booklore.model.enums;

import java.util.Locale;
import java.util.Optional;

public enum DownloadFormat {
    EPUB("epub"),
    PDF("pdf"),
    CBZ("cbz"),
    CBR("cbr"),
    CB7("cb7"),
    MOBI("mobi"),
    AZW("azw"),
    AZW3("azw3"),
    FB2("fb2"),
    UNKNOWN("");

    private final String extension;

    DownloadFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public boolean isArchiveComicFormat() {
        return this == CBZ || this == CBR || this == CB7;
    }

    public static Optional<DownloadFormat> fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (DownloadFormat format : values()) {
            if (format != UNKNOWN && lower.endsWith("." + format.extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    public static DownloadFormat fromText(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(".", "");
        for (DownloadFormat format : values()) {
            if (format.name().equals(normalized) || format.extension.toUpperCase(Locale.ROOT).equals(normalized)) {
                return format;
            }
        }
        return UNKNOWN;
    }
}
