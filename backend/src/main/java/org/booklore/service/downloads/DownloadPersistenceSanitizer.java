package org.booklore.service.downloads;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DownloadPersistenceSanitizer {

    static final int EXTERNAL_ID_MAX_LENGTH = 512;
    static final int TITLE_MAX_LENGTH = 512;
    static final int SERIES_NAME_MAX_LENGTH = 512;
    static final int ISBN_MAX_LENGTH = 32;
    static final int LANGUAGE_MAX_LENGTH = 32;

    private static final String HASH_PREFIX = "sha256:";

    private DownloadPersistenceSanitizer() {
    }

    static String externalId(String value) {
        String normalized = optionalText(value, EXTERNAL_ID_MAX_LENGTH);
        if (normalized == null || normalized.equals(value.trim())) {
            return normalized;
        }
        return HASH_PREFIX + sha256Hex(value.trim());
    }

    static String requiredText(String value, String fallback, int maxLength) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            normalized = fallback;
        }
        return truncate(normalized, maxLength);
    }

    static String optionalText(String value, int maxLength) {
        String normalized = blankToNull(value);
        return normalized == null ? null : truncate(normalized, maxLength);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String truncate(String value, int maxLength) {
        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
