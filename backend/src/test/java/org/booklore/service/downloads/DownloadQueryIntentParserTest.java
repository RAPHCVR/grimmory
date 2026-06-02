package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSequenceNumberType;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadQueryIntentParserTest {

    private final DownloadQueryIntentParser parser = new DownloadQueryIntentParser();

    @Test
    void enrich_mangaQueryWithTrailingVolume_setsSeriesNumberAndCleanTitle() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build());

        assertEquals("Dragon Ball Super", enriched.getQuery());
        assertEquals("Dragon Ball Super", enriched.getTitle());
        assertEquals("Dragon Ball Super", enriched.getSeriesName());
        assertEquals(24f, enriched.getSeriesNumber());
        assertEquals(DownloadSequenceNumberType.VOLUME, enriched.getSequenceNumberType());
        assertEquals("Dragon Ball Super 24", enriched.getOriginalQuery());
    }

    @Test
    void enrich_mangaQueryWithExplicitChapter_setsChapterIntent() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Dragon Ball Super chapitre 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build());

        assertEquals("Dragon Ball Super", enriched.getQuery());
        assertEquals(24f, enriched.getSeriesNumber());
        assertEquals(DownloadSequenceNumberType.CHAPTER, enriched.getSequenceNumberType());
    }

    @Test
    void enrich_mangaQueryWithVolumeMarkerAndTrailingAuthor_keepsSeriesTitleAndVolume() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Bonne Nuit Punpun - Tome 3 Inio Asano")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build());

        assertEquals("Bonne Nuit Punpun", enriched.getQuery());
        assertEquals("Bonne Nuit Punpun", enriched.getTitle());
        assertEquals("Bonne Nuit Punpun", enriched.getSeriesName());
        assertEquals(3f, enriched.getSeriesNumber());
        assertEquals(DownloadSequenceNumberType.VOLUME, enriched.getSequenceNumberType());
        assertEquals("Bonne Nuit Punpun - Tome 3 Inio Asano", enriched.getOriginalQuery());
    }

    @Test
    void enrich_webtoonEpisodeMarker_setsSeriesNumber() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Lore Olympus ep 1")
                .contentKind(DownloadContentKind.WEBTOON)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build());

        assertEquals("Lore Olympus", enriched.getQuery());
        assertEquals(1f, enriched.getSeriesNumber());
        assertEquals(DownloadSequenceNumberType.EPISODE, enriched.getSequenceNumberType());
    }

    @Test
    void enrich_bookQuery_doesNotTreatYearAsVolume() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Dune 1965")
                .contentKind(DownloadContentKind.BOOK)
                .preferredFormats(List.of(DownloadFormat.EPUB))
                .build());

        assertEquals("Dune 1965", enriched.getQuery());
        assertNull(enriched.getSeriesNumber());
    }
}
