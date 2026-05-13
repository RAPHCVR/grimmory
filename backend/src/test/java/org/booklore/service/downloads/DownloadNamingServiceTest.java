package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DownloadNamingServiceTest {

    private final DownloadNamingService service = new DownloadNamingService();

    @Test
    void buildFinalFileName_book_usesAuthorTitleYearIsbn() {
        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Dune")
                .authors(List.of("Frank Herbert"))
                .publishedYear(1965)
                .isbn("9780441172719")
                .contentKind(DownloadContentKind.BOOK)
                .build();

        assertEquals("Frank Herbert - Dune (1965) [9780441172719].epub",
                service.buildFinalFileName(result, DownloadFormat.EPUB));
    }

    @Test
    void buildFinalFileName_manga_removesInvalidFilesystemCharacters() {
        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Chapter: 8/1?")
                .seriesName("One/Punch*Man")
                .seriesNumber(8.1f)
                .contentKind(DownloadContentKind.MANGA)
                .build();

        String fileName = service.buildFinalFileName(result, DownloadFormat.CBZ);

        assertEquals("OnePunchMan - v08.1 - Chapter 81.cbz", fileName);
        assertFalse(fileName.contains("/"));
        assertFalse(fileName.contains(":"));
    }

    @Test
    void buildFinalFileName_manga_stripsTrailingSeriesPunctuation() {
        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("One Piece, Vol. 100")
                .seriesName("One Piece,")
                .seriesNumber(100f)
                .contentKind(DownloadContentKind.MANGA)
                .build();

        assertEquals("One Piece - v100.epub",
                service.buildFinalFileName(result, DownloadFormat.EPUB));
    }

    @Test
    void buildFinalFileName_manga_keepsUsefulSubtitleAfterRepeatedSeriesAndVolume() {
        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Dragon Ball Super - Vol.24 - Full Color (Ch101 - Ch104)")
                .seriesName("Dragon Ball Super")
                .seriesNumber(24f)
                .contentKind(DownloadContentKind.MANGA)
                .build();

        assertEquals("Dragon Ball Super - v24 - Full Color (Ch101 - Ch104).cbz",
                service.buildFinalFileName(result, DownloadFormat.CBZ));
    }

    @Test
    void buildFinalFileName_webtoon_usesChapterNumber() {
        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("The Tower")
                .seriesName("Tower of God")
                .seriesNumber(12f)
                .contentKind(DownloadContentKind.WEBTOON)
                .build();

        assertEquals("Tower of God - Ch 012 - The Tower.cbz",
                service.buildFinalFileName(result, DownloadFormat.CBZ));
    }
}
