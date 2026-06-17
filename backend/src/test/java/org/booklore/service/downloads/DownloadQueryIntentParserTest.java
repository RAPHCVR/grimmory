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
    void enrich_autoQueryWithDefaultMixedFormats_doesNotTreatBookSubtitleTomeAsSequentialIntent() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Le Temps des Tempêtes - Tome 1")
                .contentKind(DownloadContentKind.AUTO)
                .preferredFormats(List.of(
                        DownloadFormat.EPUB,
                        DownloadFormat.PDF,
                        DownloadFormat.CBZ,
                        DownloadFormat.CBR,
                        DownloadFormat.CB7,
                        DownloadFormat.MOBI,
                        DownloadFormat.AZW,
                        DownloadFormat.AZW3,
                        DownloadFormat.FB2
                ))
                .build());

        assertEquals("Le Temps des Tempêtes - Tome 1", enriched.getQuery());
        assertNull(enriched.getSeriesNumber());
        assertNull(enriched.getSeriesNumberEnd());
        assertEquals(DownloadSequenceNumberType.AUTO, enriched.getSequenceNumberType());
    }

    @Test
    void enrich_autoQueryWithOnlyArchiveComicFormats_treatsTomeAsSequentialIntent() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Bonne Nuit Punpun - Tome 3 Inio Asano")
                .contentKind(DownloadContentKind.AUTO)
                .preferredFormats(List.of(DownloadFormat.CBZ, DownloadFormat.CBR))
                .build());

        assertEquals("Bonne Nuit Punpun", enriched.getQuery());
        assertEquals("Bonne Nuit Punpun", enriched.getTitle());
        assertEquals(3f, enriched.getSeriesNumber());
        assertEquals(DownloadSequenceNumberType.VOLUME, enriched.getSequenceNumberType());
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
    void enrich_webtoonEpisodeRange_setsSeriesNumberRange() {
        DownloadSearchCriteria enriched = parser.enrich(DownloadSearchCriteria.builder()
                .query("Surviving the Game as a Barbarian ep 145-146")
                .contentKind(DownloadContentKind.WEBTOON)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build());

        assertEquals("Surviving the Game as a Barbarian", enriched.getQuery());
        assertEquals("Surviving the Game as a Barbarian", enriched.getTitle());
        assertEquals("Surviving the Game as a Barbarian", enriched.getSeriesName());
        assertEquals(145f, enriched.getSeriesNumber());
        assertEquals(146f, enriched.getSeriesNumberEnd());
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
