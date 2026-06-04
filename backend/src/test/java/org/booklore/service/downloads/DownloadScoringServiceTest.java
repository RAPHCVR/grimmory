package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSequenceNumberType;
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

    @Test
    void score_releaseTitleWithExtraWords_stillMatchesQueryTokens() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece 100")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("[ENG] One Piece - Vol. 100 (FULL COLOR Digital Colored Comics)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .downloadUrl("http://localhost:9696/1/download?file=one-piece-100")
                .sizeBytes(159_593_264L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 50);
        assertTrue(score.getReasons().contains("+35 title strong match"));
        assertTrue(score.getReasons().contains("+20 requested volume/chapter number match"));
    }

    @Test
    void score_explicitChapterRequestPenalizesVolumeMarker() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece chapter 100")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(100f)
                .sequenceNumberType(DownloadSequenceNumberType.CHAPTER)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult volumeResult = NormalizedDownloadResult.builder()
                .title("[ENG] One Piece - Vol. 100 (FULL COLOR Digital Colored Comics)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("http://localhost:9696/1/download?file=one-piece-100")
                .sizeBytes(159_593_264L)
                .build();

        NormalizedDownloadResult chapterResult = NormalizedDownloadResult.builder()
                .title("The Legend Begins")
                .seriesName("One Piece")
                .seriesNumber(100f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                .downloadUrl("chapter-100")
                .build();

        var volumeScore = service.score(criteria, volumeResult);
        var chapterScore = service.score(criteria, chapterResult);

        assertTrue(volumeScore.getScore() < 50);
        assertTrue(chapterScore.getScore() > volumeScore.getScore());
        assertTrue(volumeScore.getReasons().contains("-60 conflicting volume/issue marker for requested chapter"));
    }

    @Test
    void score_explicitChapterRequestPenalizesBundleRange() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece chapter 100")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(100f)
                .sequenceNumberType(DownloadSequenceNumberType.CHAPTER)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult bundleRange = NormalizedDownloadResult.builder()
                .title("One Piece v001-111 + 1134-1176 (2003-2026) (Digital) (1r0n)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(31_245_887_488L)
                .build();

        NormalizedDownloadResult exactChapter = NormalizedDownloadResult.builder()
                .title("One Piece - Chapter 100")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:123456")
                .sizeBytes(80_000_000L)
                .build();

        var bundleScore = service.score(criteria, bundleRange);
        var chapterScore = service.score(criteria, exactChapter);

        assertTrue(bundleScore.getScore() < 30);
        assertTrue(chapterScore.getScore() > bundleScore.getScore());
        assertTrue(bundleScore.getReasons().contains("-65 bundled range cannot satisfy requested chapter exactly"));
        assertTrue(chapterScore.getReasons().contains("+20 requested chapter number match"));
    }

    @Test
    void score_explicitChapterRequestWithoutChapterNumberScoresZero() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece chapter 100")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(100f)
                .sequenceNumberType(DownloadSequenceNumberType.CHAPTER)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("One Piece - Ace's Story - The Manga (2024) (Digital) (1r0n)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(280_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertEquals(0, score.getScore());
        assertTrue(score.getReasons().contains("-65 missing requested chapter number"));
    }

    @Test
    void score_numberedRelease_ranksExactVolumeAboveBundleRange() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece 100")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult exactVolume = NormalizedDownloadResult.builder()
                .title("[ENG] One Piece - Vol. 100 (FULL COLOR Digital Colored Comics)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .downloadUrl("http://localhost:9696/1/download?file=one-piece-100")
                .sizeBytes(159_593_264L)
                .build();

        NormalizedDownloadResult bundleRange = NormalizedDownloadResult.builder()
                .title("One Piece v001-100 (Digital HD)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .downloadUrl("http://localhost:9696/1/download?file=one-piece-001-100")
                .sizeBytes(31_245_887_488L)
                .build();

        var exactScore = service.score(criteria, exactVolume);
        var bundleScore = service.score(criteria, bundleRange);

        assertTrue(exactScore.getScore() > bundleScore.getScore());
        assertTrue(bundleScore.getReasons().contains("-5 bundled range contains requested number"));
    }

    @Test
    void score_explicitVolumeRequestPenalizesBundleRangeBeforeExactMarker() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("one piece tome 1")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(1f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult bundleRange = NormalizedDownloadResult.builder()
                .title("One Piece v001-111 + 1134-1176 (2003-2026) (Digital) (1r0n)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(31_245_887_488L)
                .build();

        NormalizedDownloadResult exactVolume = NormalizedDownloadResult.builder()
                .title("One Piece Vol. 1")
                .seriesName("One Piece")
                .seriesNumber(1f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:123456")
                .sizeBytes(150_000_000L)
                .build();

        var bundleScore = service.score(criteria, bundleRange);
        var exactScore = service.score(criteria, exactVolume);

        assertTrue(bundleScore.getScore() < exactScore.getScore());
        assertTrue(bundleScore.getScore() < 50);
        assertTrue(bundleScore.getReasons().contains("-45 bundled range cannot satisfy requested volume exactly"));
        assertTrue(bundleScore.getReasons().stream().noneMatch("+20 requested volume number match"::equals));
    }

    @Test
    void score_explicitVolumeRequestPenalizesPrefixedBundleRange() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("one piece tome 1")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(1f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("One Piece - Digital Colored Comics+Cover Stories V1 Batch (v000-v105 & ch0035-1078)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(31_245_887_488L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-45 bundled range cannot satisfy requested volume exactly"));
        assertTrue(score.getReasons().stream().noneMatch("+20 requested volume number match"::equals));
    }


    @Test
    void score_explicitMangaVolumeRequestPenalizesSpinOffNovels() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("naruto tome 1")
                .title("Naruto")
                .seriesName("Naruto")
                .seriesNumber(1f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.EPUB))
                .build();

        NormalizedDownloadResult spinOffNovel = NormalizedDownloadResult.builder()
                .title("Daylight (Naruto Novels)")
                .seriesName("Naruto: Itachi's Story")
                .seriesNumber(1f)
                .format(DownloadFormat.EPUB)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                .downloadUrl("http://stacks/download/naruto-itachi-story")
                .build();

        var score = service.score(criteria, spinOffNovel);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-45 novel/light-novel payload for sequential art request"));
        assertTrue(score.getReasons().contains("-40 spin-off series cannot satisfy main-series volume exactly"));
    }

    @Test
    void score_explicitVolumeRequestPenalizesLocalizedAndPlusBundleRanges() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("naruto tome 1")
                .title("Naruto")
                .seriesName("Naruto")
                .seriesNumber(1f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult localizedRange = NormalizedDownloadResult.builder()
                .title("Naruto (Tome 1 à 72 + Naruto Gaiden) - VF - .cbz")
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(5_000_000_000L)
                .build();
        NormalizedDownloadResult plusRange = NormalizedDownloadResult.builder()
                .title("Naruto v01+72 (Colored) (Digital) (PZG)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:123456")
                .sizeBytes(5_000_000_000L)
                .build();

        var localizedScore = service.score(criteria, localizedRange);
        var plusScore = service.score(criteria, plusRange);

        assertTrue(localizedScore.getScore() < 50);
        assertTrue(plusScore.getScore() < 50);
        assertTrue(localizedScore.getReasons().contains("-45 bundled range cannot satisfy requested volume exactly"));
        assertTrue(plusScore.getReasons().contains("-45 bundled range cannot satisfy requested volume exactly"));
        assertTrue(localizedScore.getReasons().stream().noneMatch("+20 requested volume number match"::equals));
        assertTrue(plusScore.getReasons().stream().noneMatch("+20 requested volume number match"::equals));
    }

    @Test
    void score_explicitVolumeRequestDoesNotTreatReleaseVersionAsVolume() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("one piece tome 1")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(1f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("One Piece Definitive Edition Re-Translation v012-023 (Colored) (Digital) (VLT) {Alabasta} {v1.0}")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(5_000_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().stream().noneMatch("+20 requested volume number match"::equals));
    }

    @Test
    void score_explicitVolumeRequestDoesNotTreatUploaderAliasDigitAsNumber() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("one piece tome 1")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(1f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("One Piece - Ace's Story - The Manga (2024) (Digital) (1r0n)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(280_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-20 missing requested volume number"));
        assertTrue(score.getReasons().stream().noneMatch("+5 requested number token present"::equals));
    }

    @Test
    void score_explicitVolumeRequestStronglyPenalizesChapterSource() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("one piece tome 1")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(1f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult chapterResult = NormalizedDownloadResult.builder()
                .title("Romance Dawn - À l'aube d'une grande aventure")
                .seriesName("One Piece")
                .seriesNumber(1f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                .downloadUrl("chapter-1")
                .build();

        var score = service.score(criteria, chapterResult);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-120 chapter/episode result for volume/issue request"));
    }

    @Test
    void score_compactVolumeMarkerMatchesRequestedNumber() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Dragon Ball Super - Digital Colored Comics v24 (2026)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(530_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 70);
        assertTrue(score.getReasons().contains("+35 title strong match"));
        assertTrue(score.getReasons().contains("+20 requested volume/chapter number match"));
    }

    @Test
    void score_sequentialResultWithoutRequestedNumberIsPenalized() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece")
                .seriesName("One Piece")
                .seriesNumber(100f)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("One Piece - Ace's Story - The Manga (2024) (Digital)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(280_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-20 missing requested volume/chapter number"));
    }

    @Test
    void score_mangaDexChapterNumberMismatchIsPenalized() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("The God of Destruction's Prophetic Dream")
                .seriesName("Dragon Ball Super #1")
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                .downloadUrl("chapter-1")
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-20 requested volume/chapter number mismatch"));
    }

    @Test
    void score_mangaDexSeriesNumberMismatchIsPenalized() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("The God of Destruction's Prophetic Dream")
                .seriesName("Dragon Ball Super")
                .seriesNumber(1f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                .downloadUrl("chapter-1")
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-20 requested volume/chapter number mismatch"));
    }

    @Test
    void score_mangaDexSeriesNumberMatchGetsMeasuredBonus() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .sequenceNumberType(DownloadSequenceNumberType.CHAPTER)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Son Goku's Evolution")
                .seriesName("Dragon Ball Super")
                .seriesNumber(24f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                .downloadUrl("chapter-24")
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 70);
        assertTrue(score.getReasons().contains("+10 requested chapter number match"));
    }

    @Test
    void score_mangaDexChapterForBareMangaVolumeRequestIsPenalized() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super")
                .seriesName("Dragon Ball Super")
                .seriesNumber(24f)
                .sequenceNumberType(DownloadSequenceNumberType.VOLUME)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult chapter = NormalizedDownloadResult.builder()
                .title("Son Goku's Evolution")
                .seriesName("Dragon Ball Super")
                .seriesNumber(24f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                .downloadUrl("chapter-24")
                .build();

        NormalizedDownloadResult volume = NormalizedDownloadResult.builder()
                .title("Dragon Ball Super - Digital Colored Comics v24 (2026)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(530_000_000L)
                .build();

        var chapterScore = service.score(criteria, chapter);
        var volumeScore = service.score(criteria, volume);

        assertTrue(volumeScore.getScore() > chapterScore.getScore());
        assertTrue(chapterScore.getReasons().contains("-120 chapter/episode result for volume/issue request"));
        assertTrue(volumeScore.getReasons().contains("+20 requested volume number match"));
    }

    @Test
    void score_pathDateDoesNotCountAsRequestedNumber() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("lgli/I:\\comics3\\emule\\2020.05.24\\Dragon Ball Super T05 (Toriyama-Toyotaro) [Manga FR].cbz")
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                .detailsUrl("https://annas-archive.test/md5/example")
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 65);
        assertTrue(score.getReasons().contains("-20 requested volume/chapter number mismatch"));
    }

    @Test
    void score_videoTorrentForMangaQueryScoresZero() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("[DeadFish] Dragon Ball Super - 24 [720p][AAC].mp4")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.BOOK)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(392_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertEquals(0, score.getScore());
        assertTrue(score.getReasons().contains("-90 unsupported media payload"));
        assertTrue(score.getReasons().contains("-30 content kind mismatch"));
    }

    @Test
    void score_tvAnimeCategoryTorrentForMangaQueryScoresZero() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Dragon Ball Super - 24 - ¡Impacto! ¡Freezer contra Son Goku! ¡El Resultado del Entrenamiento! [Castellano]")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.BOOK)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .rawJson("{\"categories\":[{\"id\":5070,\"name\":\"TV/Anime\"},{\"id\":2020,\"name\":\"Movies/Other\"}]}")
                .sizeBytes(570_215_616L)
                .build();

        var score = service.score(criteria, result);

        assertEquals(0, score.getScore());
        assertTrue(score.getReasons().contains("-90 unsupported media payload"));
        assertTrue(score.getReasons().contains("-30 content kind mismatch"));
    }

    @Test
    void score_subtitledFullHdTorrentWithoutExtensionScoresZero() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Dragon Ball Super 24")
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("DBF - Dragon Ball Super #24 FULLHD - Sub-Ita -")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.BOOK)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(561_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertEquals(0, score.getScore());
        assertTrue(score.getReasons().contains("-90 unsupported media payload"));
    }

    @Test
    void score_gameRepackTorrentForMangaQueryScoresZero() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece")
                .seriesName("One Piece")
                .seriesNumber(100f)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("ONE PIECE ODYSSEY: Deluxe Edition (+ 6 DLCs, MULTi15) [FitGirl Repack]")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(30_000_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertEquals(0, score.getScore());
        assertTrue(score.getReasons().contains("-90 unsupported media payload"));
    }

    @Test
    void score_adultVideoNoiseTorrentForMangaQueryScoresZero() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece chapter 100")
                .title("One Piece")
                .seriesName("One Piece")
                .seriesNumber(100f)
                .sequenceNumberType(DownloadSequenceNumberType.CHAPTER)
                .contentKind(DownloadContentKind.MANGA)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("HD GS 323 cleaning staff began my time one piece pants girl into the adult toys in Masturbation")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(570_000_000L)
                .build();

        var score = service.score(criteria, result);

        assertEquals(0, score.getScore());
        assertTrue(score.getReasons().contains("-90 unsupported media payload"));
        assertTrue(score.getReasons().contains("-65 missing requested chapter number"));
    }

    @Test
    void score_directGalleryDlUrl_acceptsInferredVisualContentKind() {
        String url = "https://www.webtoons.com/fr/fantasy/tower-of-god/saison-3-ep-235/viewer?title_no=1832&episode_no=652";
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl(url)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Saison 3 Ep 235")
                .seriesName("Tower of God")
                .seriesNumber(235f)
                .contentKind(DownloadContentKind.WEBTOON)
                .format(DownloadFormat.CBZ)
                .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                .downloadUrl(url)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 90);
        assertTrue(score.getReasons().contains("+80 direct URL exact match"));
        assertTrue(score.getReasons().contains("+5 content kind inferred automatically"));
    }

    @Test
    void score_webtoonEpisode_prefersNativeGalleryDlOverCompiledStacksVolume() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Lore Olympus")
                .seriesName("Lore Olympus")
                .seriesNumber(1f)
                .contentKind(DownloadContentKind.WEBTOON)
                .preferredFormats(List.of(DownloadFormat.CBZ, DownloadFormat.PDF))
                .build();

        NormalizedDownloadResult nativeEpisode = NormalizedDownloadResult.builder()
                .title("Episode 1")
                .seriesName("Lore Olympus")
                .seriesNumber(1f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.WEBTOON)
                .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                .downloadUrl("https://www.webtoons.com/en/romance/lore-olympus/episode-1/viewer?title_no=1320&episode_no=1")
                .build();

        NormalizedDownloadResult compiledVolume = NormalizedDownloadResult.builder()
                .title("Lore Olympus: Volume One (In Black and White)")
                .seriesName("Lore Olympus")
                .seriesNumber(1f)
                .format(DownloadFormat.PDF)
                .contentKind(DownloadContentKind.WEBTOON)
                .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                .detailsUrl("https://annas-archive.test/md5/lore-olympus-volume-one")
                .build();

        var nativeScore = service.score(criteria, nativeEpisode);
        var compiledScore = service.score(criteria, compiledVolume);

        assertTrue(nativeScore.getScore() > compiledScore.getScore());
        assertTrue(compiledScore.getReasons().contains("-60 non-native webtoon episode source"));
    }

    @Test
    void score_canonicalWebtoonEpisodeCriteriaPrefersEpisodeTitleOverSeriesIntroTie() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Lore Olympus")
                .seriesName("Lore Olympus")
                .seriesNumber(1f)
                .contentKind(DownloadContentKind.WEBTOON)
                .preferredFormats(List.of(DownloadFormat.CBZ, DownloadFormat.PDF))
                .build();

        NormalizedDownloadResult exactEpisodeTitle = NormalizedDownloadResult.builder()
                .title("Episode 1")
                .seriesName("Lore Olympus")
                .seriesNumber(1f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.WEBTOON)
                .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                .downloadUrl("https://www.webtoons.com/en/romance/lore-olympus/episode-1/viewer?title_no=1320&episode_no=1")
                .build();

        NormalizedDownloadResult introTitle = NormalizedDownloadResult.builder()
                .title("Introduction")
                .seriesName("Lore Olympus")
                .seriesNumber(1f)
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.WEBTOON)
                .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                .downloadUrl("https://www.webtoons.com/en/romance/lore-olympus/introduction/viewer?title_no=1320&episode_no=1")
                .build();

        var exactScore = service.score(criteria, exactEpisodeTitle);
        var introScore = service.score(criteria, introTitle);

        assertTrue(exactScore.getScore() > introScore.getScore());
        assertTrue(introScore.getReasons().contains("-50 explicit episode marker missing from result title"));
    }

    @Test
    void score_webtoonSearchDoesNotClampLooseTitleContainmentToPerfectMatch() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("solo leveling")
                .seriesName("Solo Leveling")
                .author("Chugong")
                .contentKind(DownloadContentKind.WEBTOON)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult exactSeries = NormalizedDownloadResult.builder()
                .title("Solo Leveling")
                .seriesName("Solo Leveling")
                .authors(List.of("Chugong"))
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.WEBTOON)
                .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                .downloadUrl("https://www.webtoons.com/en/action/solo-leveling/list?title_no=9999")
                .build();

        NormalizedDownloadResult looseContainment = NormalizedDownloadResult.builder()
                .title("Walmart Solo Leveling")
                .seriesName("Walmart Solo Leveling")
                .authors(List.of("Different Creator"))
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.WEBTOON)
                .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                .downloadUrl("https://www.webtoons.com/en/canvas/walmart-solo-leveling/list?title_no=8888")
                .build();

        var exactScore = service.score(criteria, exactSeries);
        var looseScore = service.score(criteria, looseContainment);

        assertEquals(100, exactScore.getScore());
        assertTrue(looseScore.getScore() < 80);
        assertTrue(exactScore.getScore() > looseScore.getScore());
        assertTrue(looseScore.getReasons().contains("+35 title strong match"));
        assertTrue(looseScore.getReasons().contains("-20 author mismatch"));
    }

    @Test
    void score_externalStacksMd5Result_doesNotRequireDownloadUrlAndMatchesAuthorQuery() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("bernard werber")
                .contentKind(DownloadContentKind.BOOK)
                .preferredFormats(List.of(DownloadFormat.EPUB))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .sourceResultId("0123456789abcdef0123456789abcdef")
                .title("Les Fourmis")
                .authors(List.of("Bernard Werber"))
                .format(DownloadFormat.EPUB)
                .contentKind(DownloadContentKind.BOOK)
                .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                .detailsUrl("https://annas-archive.gl/md5/0123456789abcdef0123456789abcdef")
                .sizeBytes(1_500_000L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 75);
        assertTrue(score.getReasons().contains("+45 query author match"));
        assertTrue(score.getReasons().contains("title scoring skipped for author query"));
        assertTrue(score.getReasons().stream().noneMatch("-35 title weak match"::equals));
        assertTrue(score.getReasons().stream().noneMatch("-100 missing download URL"::equals));
    }

    @Test
    void score_universalQueryWithTitleAndAuthorMatchesCombinedMetadata() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Les Fourmis Bernard Werber")
                .contentKind(DownloadContentKind.BOOK)
                .preferredFormats(List.of(DownloadFormat.EPUB))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .sourceResultId("6fc83a82e765e3808aa55102b0894275")
                .title("Les Fourmis (Les Fourmis, Tome 1) (Le Livre de Poche) (French Edition)")
                .authors(List.of("Bernard Werber Werber"))
                .format(DownloadFormat.EPUB)
                .contentKind(DownloadContentKind.BOOK)
                .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                .detailsUrl("https://annas-archive.gl/md5/6fc83a82e765e3808aa55102b0894275")
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 60);
        assertTrue(score.getReasons().contains("+35 title strong match"));
    }

    @Test
    void score_universalQueryWithDirtyAuthorFieldStillScoresTitle() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Les Fourmis Bernard Werber")
                .contentKind(DownloadContentKind.BOOK)
                .preferredFormats(List.of(DownloadFormat.EPUB))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .sourceResultId("6fc83a82e765e3808aa55102b0894275")
                .title("Les Fourmis (Les Fourmis, Tome 1) (Le Livre de Poche) (French Edition)")
                .authors(List.of("Les Fourmis Bernard Werber"))
                .format(DownloadFormat.EPUB)
                .contentKind(DownloadContentKind.BOOK)
                .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                .detailsUrl("https://annas-archive.gl/md5/6fc83a82e765e3808aa55102b0894275")
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 80);
        assertTrue(score.getReasons().contains("+35 title strong match"));
        assertTrue(score.getReasons().stream().noneMatch("title scoring skipped for author query"::equals));
    }

    @Test
    void score_autoContentKindAcceptsInferredMangaAndDefersTorrentFormat() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("One Piece 100")
                .contentKind(DownloadContentKind.AUTO)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("[ENG] One Piece - Vol. 100 (FULL COLOR Digital Colored Comics)")
                .format(DownloadFormat.UNKNOWN)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.TORRENT)
                .downloadUrl("magnet:?xt=urn:btih:abcdef")
                .sizeBytes(159_593_264L)
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() >= 75);
        assertTrue(score.getReasons().contains("+5 content kind inferred automatically"));
        assertTrue(score.getReasons().contains("+5 visual content kind evidence"));
        assertTrue(score.getReasons().contains("+5 torrent payload format deferred"));
    }

    @Test
    void score_disconnectedQueryTokensDoNotCreateStrongTitleMatch() {
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .query("Tower of God")
                .contentKind(DownloadContentKind.AUTO)
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Welcome to [The Lesser Tower of Clubs]")
                .seriesName("The Female God of Babel: KAMISAMA Club in Tower of Babel")
                .format(DownloadFormat.CBZ)
                .contentKind(DownloadContentKind.MANGA)
                .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                .downloadUrl("chapter-id")
                .build();

        var score = service.score(criteria, result);

        assertTrue(score.getScore() < 50);
        assertTrue(score.getReasons().contains("-35 title weak match"));
    }
}
