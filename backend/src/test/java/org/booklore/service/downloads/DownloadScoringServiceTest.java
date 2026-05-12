package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadScoringServiceTest {

    private final DownloadScoringService service = new DownloadScoringService();

    @Test
    void score_exactIsbnAndPreferredFormat_clampsTo100() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .title("Dune")
                .author("Frank Herbert")
                .isbn("9780441172719")
                .contentKind(DownloadContentKind.BOOK)
                .preferredFormats(List.of(DownloadFormat.EPUB))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Dune")
                .authors(List.of("Frank Herbert"))
                .isbn("978-0-441-17271-9")
                .format(DownloadFormat.EPUB)
                .contentKind(DownloadContentKind.BOOK)
                .sizeBytes(1_000_000L)
                .downloadUrl("https://example.test/dune.epub")
                .build();

        var score = service.score(criteria, result);

        assertEquals(100, score.getScore());
        assertTrue(score.getReasons().contains("+100 ISBN exact match"));
        assertTrue(score.getReasons().contains("+20 preferred format"));
    }

    @Test
    void score_wrongFormatAndWeakTitle_penalizesResult() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .title("Dune")
                .author("Frank Herbert")
                .contentKind(DownloadContentKind.BOOK)
                .preferredFormats(List.of(DownloadFormat.EPUB))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Foundation")
                .authors(List.of("Isaac Asimov"))
                .format(DownloadFormat.PDF)
                .contentKind(DownloadContentKind.BOOK)
                .downloadUrl("https://example.test/foundation.pdf")
                .sizeBytes(1_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 30);
        assertTrue(score.getReasons().contains("-50 wrong format"));
        assertTrue(score.getReasons().contains("-35 title weak match"));
    }
}
