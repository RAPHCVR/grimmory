package org.booklore.service.downloads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DownloadPersistenceSanitizerTest {

    @Test
    void externalId_hashesValuesThatExceedDatabaseLimit() {
        String externalId = "magnet:?xt=urn:btih:" + "a".repeat(600);

        String sanitized = DownloadPersistenceSanitizer.externalId(externalId);

        assertNotNull(sanitized);
        assertTrue(sanitized.startsWith("sha256:"));
        assertEquals(71, sanitized.length());
    }

    @Test
    void requiredText_truncatesByCodePointWithoutBreakingUnicode() {
        String title = "😀".repeat(513);

        String sanitized = DownloadPersistenceSanitizer.requiredText(title, "Untitled", DownloadPersistenceSanitizer.TITLE_MAX_LENGTH);

        assertEquals(512, sanitized.codePointCount(0, sanitized.length()));
        assertDoesNotThrow(() -> sanitized.codePointAt(sanitized.length() - 2));
    }
}
