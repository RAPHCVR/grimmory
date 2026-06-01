package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class DownloadContentClassifier {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern MANGA_RELEASE_MARKER = Pattern.compile("(?i)(?:\\bvol(?:ume)?\\b|\\bv0?\\d{2,4}\\b|\\btom[eo]\\b|\\bch(?:apter)?\\b|\\bchapitre\\b|digital colored comics|one[- ]?shot|tankou?bon)");
    private static final Pattern UNSUPPORTED_MEDIA_MARKER = Pattern.compile("(?i)(?:\\bmp4\\b|\\bmkv\\b|\\bavi\\b|\\bmov\\b|\\bwmv\\b|\\bflac\\b|\\bmp3\\b|\\baac\\b|\\bopus\\b|\\b480p\\b|\\b720p\\b|\\b1080p\\b|\\b2160p\\b|\\bfullhd\\b|\\bbdrip\\b|\\bwebrip\\b|\\bhdtv\\b|\\bbluray\\b|\\bblu ray\\b|\\bx264\\b|\\bx265\\b|\\bhevc\\b|\\bh\\s?264\\b|\\bh\\s?265\\b|\\b10bit\\b|\\bdual audio\\b|\\bsubbed\\b|\\bsoftsubs?\\b|\\bvostfr\\b|\\bsub ita\\b|\\bsub esp\\b|\\bsoundtrack\\b|\\bost\\b|\\bs\\d{1,2}\\s?e\\d{1,3}\\b|\\btv anime\\b|\\bmovies other\\b|\\bfitgirl\\b|\\bdodi\\b|\\belamigos\\b|\\bsteamrip\\b|\\bskidrow\\b|\\breloaded\\b|\\bplaza\\b|\\brazor1911\\b|\\bcodex\\b|\\bgame repack\\b|\\bxxx\\b|\\bporn(?:o|ography)?\\b|\\bjav\\b|\\badult toys?\\b|\\berotic\\b|\\bnaked\\b|\\bundress\\b|\\bmasturbation\\b|\\btits?\\b|\\bbreasts?\\b|\\bhard\\s+ass\\b|\\bhot\\s+ass\\b|\\badult\\s+video\\b|\\bsex\\s+video\\b)");

    public DownloadContentKind resolve(DownloadContentKind requested,
                                       DownloadContentKind inferred,
                                       DownloadContentKind fallback) {
        if (isConcrete(inferred)) {
            return inferred;
        }
        if (isConcrete(requested)) {
            return requested;
        }
        return isConcrete(fallback) ? fallback : DownloadContentKind.BOOK;
    }

    public boolean isAuto(DownloadContentKind contentKind) {
        return contentKind == null || contentKind.isAuto();
    }

    public boolean isConcrete(DownloadContentKind contentKind) {
        return contentKind != null && !contentKind.isAuto();
    }

    public boolean isSequentialArt(DownloadContentKind contentKind) {
        return contentKind != null && contentKind.isSequentialArt();
    }

    public DownloadContentKind sourceDefault(DownloadSourceType sourceType) {
        if (sourceType == DownloadSourceType.MANGADEX) {
            return DownloadContentKind.MANGA;
        }
        if (sourceType == DownloadSourceType.ANNAS_ARCHIVE_API) {
            return DownloadContentKind.BOOK;
        }
        return null;
    }

    public DownloadContentKind infer(DownloadSourceType sourceType,
                                     String sourceName,
                                     String title,
                                     String seriesName,
                                     String detailsUrl,
                                     String downloadUrl,
                                     DownloadFormat format,
                                     DownloadAcquisitionType acquisitionType,
                                     String rawText) {
        String primaryEvidence = normalize(String.join(" ",
                safe(sourceName),
                safe(title),
                safe(seriesName)
        ));
        String evidence = normalize(String.join(" ",
                safe(sourceName),
                safe(title),
                safe(seriesName),
                safe(detailsUrl),
                safe(downloadUrl),
                safe(rawText)
        ));

        if (sourceType == DownloadSourceType.MANGADEX) {
            return DownloadContentKind.MANGA;
        }

        if (isUnsupportedMediaEvidence(evidence, acquisitionType, format)) {
            return DownloadContentKind.BOOK;
        }

        if (containsAny(evidence, "webtoon", "webtoons", "webcomic", "tapas")) {
            return DownloadContentKind.WEBTOON;
        }

        if (isMangaEvidence(primaryEvidence) || isMangaEvidence(evidence)) {
            return DownloadContentKind.MANGA;
        }

        if (format != null && format.isArchiveComicFormat() && hasMangaReleaseMarker(primaryEvidence)) {
            return DownloadContentKind.MANGA;
        }

        if (isComicEvidence(primaryEvidence) || isComicEvidence(evidence)) {
            return DownloadContentKind.COMIC;
        }

        if (format != null && format.isArchiveComicFormat()) {
            return DownloadContentKind.COMIC;
        }

        if (sourceType == DownloadSourceType.ANNAS_ARCHIVE_API || isBookFormat(format) || containsAny(evidence, "ebook", "isbn", "epub", "mobi", "azw3", "fb2")) {
            return DownloadContentKind.BOOK;
        }

        if (acquisitionType == DownloadAcquisitionType.CLI_GALLERY_DL) {
            return DownloadContentKind.COMIC;
        }

        return sourceDefault(sourceType);
    }

    private boolean isMangaEvidence(String evidence) {
        if (containsAny(evidence,
                "manga",
                "mangadex",
                "mangaupdates",
                "scanlation",
                "tankobon",
                "tankoubon",
                "shonen",
                "shounen",
                "seinen",
                "shojo",
                "shoujo",
                "josei")) {
            return true;
        }

        return containsAny(evidence, "nyaa", "nyaasi")
                && MANGA_RELEASE_MARKER.matcher(evidence).find();
    }

    private boolean hasMangaReleaseMarker(String evidence) {
        return MANGA_RELEASE_MARKER.matcher(evidence).find();
    }

    private boolean isComicEvidence(String evidence) {
        return containsAny(evidence,
                "comic",
                "comics",
                "comicvine",
                "graphic novel",
                "bande dessinee",
                "marvel",
                "dc comics",
                "image comics",
                "bd numerique",
                "fumetti");
    }

    private boolean isBookFormat(DownloadFormat format) {
        return format == DownloadFormat.EPUB
                || format == DownloadFormat.PDF
                || format == DownloadFormat.MOBI
                || format == DownloadFormat.AZW
                || format == DownloadFormat.AZW3
                || format == DownloadFormat.FB2;
    }

    private boolean isUnsupportedMediaEvidence(String evidence, DownloadAcquisitionType acquisitionType, DownloadFormat format) {
        boolean externalPayload = acquisitionType == DownloadAcquisitionType.TORRENT || acquisitionType == DownloadAcquisitionType.NZB;
        boolean unknownFormat = format == null || format == DownloadFormat.UNKNOWN;
        return (externalPayload || unknownFormat) && UNSUPPORTED_MEDIA_MARKER.matcher(evidence).find();
    }

    private boolean containsAny(String value, String... needles) {
        return Arrays.stream(needles).anyMatch(value::contains);
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return NON_ALNUM.matcher(normalized).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
