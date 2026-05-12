package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadContentClassifierTest {

    private final DownloadContentClassifier classifier = new DownloadContentClassifier();

    @Test
    void infer_prowlarrNyaaVolumeRelease_returnsManga() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.PROWLARR_TORZNAB,
                "Prowlarr Local",
                "[ENG] One Piece - Vol. 100 (FULL COLOR Digital Colored Comics)",
                null,
                null,
                "magnet:?xt=urn:btih:abcdef",
                DownloadFormat.UNKNOWN,
                DownloadAcquisitionType.TORRENT,
                "{\"indexer\":\"Nyaa\"}"
        );

        assertEquals(DownloadContentKind.MANGA, kind);
    }

    @Test
    void infer_archiveComicFormatWithoutMangaEvidence_returnsComic() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.PROWLARR_TORZNAB,
                "Prowlarr",
                "Batman 001 (2024) (Digital)",
                null,
                null,
                null,
                DownloadFormat.CBZ,
                DownloadAcquisitionType.TORRENT,
                "{\"category\":\"Comics\"}"
        );

        assertEquals(DownloadContentKind.COMIC, kind);
    }

    @Test
    void infer_nyaaAnimeReleaseVersionDoesNotBecomeManga() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.PROWLARR_TORZNAB,
                "Prowlarr",
                "[EMBER] Tower of God S02E24 [1080p] [HEVC WEBRip DDP] V2",
                null,
                null,
                "magnet:?xt=urn:btih:abcdef",
                DownloadFormat.UNKNOWN,
                DownloadAcquisitionType.TORRENT,
                "{\"indexer\":\"Nyaa\"}"
        );

        assertEquals(DownloadContentKind.BOOK, kind);
    }

    @Test
    void infer_prowlarrSubbedEpisodeWithoutExtensionDoesNotBecomeManga() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.PROWLARR_TORZNAB,
                "Prowlarr",
                "DBF - Dragon Ball Super #24 FULLHD - Sub-Ita -",
                null,
                null,
                "magnet:?xt=urn:btih:abcdef",
                DownloadFormat.UNKNOWN,
                DownloadAcquisitionType.TORRENT,
                "{\"indexer\":\"Nyaa\"}"
        );

        assertEquals(DownloadContentKind.BOOK, kind);
    }

    @Test
    void infer_prowlarrTvAnimeCategoryDoesNotBecomeManga() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.PROWLARR_TORZNAB,
                "Prowlarr",
                "Dragon Ball Super - 24 - ¡Impacto! ¡Freezer contra Son Goku! ¡El Resultado del Entrenamiento! [Castellano]",
                null,
                "https://nyaa.si/view/914292",
                "magnet:?xt=urn:btih:abcdef",
                DownloadFormat.UNKNOWN,
                DownloadAcquisitionType.TORRENT,
                "{\"categories\":[{\"id\":5070,\"name\":\"TV/Anime\"},{\"id\":2020,\"name\":\"Movies/Other\"}]}"
        );

        assertEquals(DownloadContentKind.BOOK, kind);
    }

    @Test
    void infer_annaArchiveMangaMarkerBeatsGenericComicsPath_returnsManga() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.ANNAS_ARCHIVE_API,
                "Anna's Archive (Stacks)",
                "lgli/L:\\comics4\\One Piece Digital Color - Tomo 12 (#100-108) HQ.cbr",
                null,
                null,
                null,
                DownloadFormat.CBR,
                DownloadAcquisitionType.EXTERNAL_STACKS,
                "comic archive path"
        );

        assertEquals(DownloadContentKind.MANGA, kind);
    }

    @Test
    void infer_annaArchiveWesternComicArchive_returnsComic() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.ANNAS_ARCHIVE_API,
                "Anna's Archive (Stacks)",
                "Batman 001 (2024) (Digital).cbz",
                null,
                null,
                null,
                DownloadFormat.CBZ,
                DownloadAcquisitionType.EXTERNAL_STACKS,
                "comic archive path"
        );

        assertEquals(DownloadContentKind.COMIC, kind);
    }

    @Test
    void infer_ebookFormat_returnsBook() {
        DownloadContentKind kind = classifier.infer(
                DownloadSourceType.ANNAS_ARCHIVE_API,
                "Anna",
                "Les Fourmis",
                null,
                null,
                null,
                DownloadFormat.EPUB,
                DownloadAcquisitionType.EXTERNAL_STACKS,
                "French EPUB Bernard Werber"
        );

        assertEquals(DownloadContentKind.BOOK, kind);
    }

    @Test
    void resolve_autoUsesInferredButExplicitUsesFallbackWhenNoInference() {
        assertEquals(DownloadContentKind.WEBTOON, classifier.resolve(DownloadContentKind.AUTO, DownloadContentKind.WEBTOON, DownloadContentKind.BOOK));
        assertEquals(DownloadContentKind.MANGA, classifier.resolve(DownloadContentKind.MANGA, null, DownloadContentKind.BOOK));
        assertEquals(DownloadContentKind.BOOK, classifier.resolve(DownloadContentKind.AUTO, null, DownloadContentKind.BOOK));
    }
}
