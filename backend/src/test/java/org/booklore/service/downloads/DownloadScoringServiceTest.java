package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadAcquisitionType;
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
        assertTrue(score.getReasons().contains("+10 requested chapter/series number match"));
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

        assertTrue(score.getScore() >= 40);
        assertTrue(score.getReasons().contains("+45 query author match"));
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
