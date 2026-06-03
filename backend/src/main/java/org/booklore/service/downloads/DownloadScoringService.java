package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadSequenceNumberType;
import org.booklore.service.downloads.dto.DownloadScoreBreakdown;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class DownloadScoringService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern NUMBER_RANGE = Pattern.compile("(?<!\\d)0*(\\d{1,5})\\s*[-–]\\s*0*(\\d{1,5})(?!\\d)");
    private static final Pattern SEQUENTIAL_MARKED_RANGE = Pattern.compile("(?iu)\\b(?:vol(?:ume)?|v|t(?:ome|omo)?|ch(?:apter)?|chapitre)\\.?\\s*0*(\\d{1,5})\\s*[-–]\\s*(?:vol(?:ume)?|v|t(?:ome|omo)?|ch(?:apter)?|chapitre)?\\.?\\s*0*(\\d{1,5})(?!\\d)");
    private static final Pattern COMPACT_NUMBER_MARKER = Pattern.compile("(?iu)\\b(vol(?:ume)?|v|t(?:ome|omo)?|ch(?:apter)?|chapitre)\\.?\\s*0*(\\d{1,5})\\b");
    private static final Pattern ANY_NUMBER_MARKER = Pattern.compile("(?iu)(?:\\b(?:vol(?:ume)?|v|t(?:ome|omo)?|ch(?:apter)?|chapitre)\\.?\\s*0*\\d{1,5}\\b|#\\s*0*\\d{1,5}\\b)");
    private static final Pattern EXPLICIT_WEBTOON_EPISODE_MARKER = Pattern.compile("(?iu)\\b(?:ep(?:isode)?|ch(?:apter)?|chapitre)\\.?\\s*0*(\\d{1,5})\\b|#\\s*0*(\\d{1,5})\\b");
    private static final Pattern UNSUPPORTED_MEDIA_MARKER = Pattern.compile("(?i)(?:\\bmp4\\b|\\bmkv\\b|\\bavi\\b|\\bmov\\b|\\bwmv\\b|\\bflac\\b|\\bmp3\\b|\\baac\\b|\\bopus\\b|\\b480p\\b|\\b720p\\b|\\b1080p\\b|\\b2160p\\b|\\bfullhd\\b|\\bbdrip\\b|\\bwebrip\\b|\\bhdtv\\b|\\bbluray\\b|\\bblu ray\\b|\\bx264\\b|\\bx265\\b|\\bhevc\\b|\\bh\\s?264\\b|\\bh\\s?265\\b|\\b10bit\\b|\\bdual audio\\b|\\bsubbed\\b|\\bsoftsubs?\\b|\\bvostfr\\b|\\bsub ita\\b|\\bsub esp\\b|\\bsoundtrack\\b|\\bost\\b|\\bs\\d{1,2}\\s?e\\d{1,3}\\b|\\btv anime\\b|\\bmovies other\\b|\\bfitgirl\\b|\\bdodi\\b|\\belamigos\\b|\\bsteamrip\\b|\\bskidrow\\b|\\breloaded\\b|\\bplaza\\b|\\brazor1911\\b|\\bcodex\\b|\\bgame repack\\b|\\bxxx\\b|\\bporn(?:o|ography)?\\b|\\bjav\\b|\\badult toys?\\b|\\berotic\\b|\\bnaked\\b|\\bundress\\b|\\bmasturbation\\b|\\btits?\\b|\\bbreasts?\\b|\\bhard\\s+ass\\b|\\bhot\\s+ass\\b|\\badult\\s+video\\b|\\bsex\\s+video\\b)");
    private static final int MIN_REASONABLE_SIZE_BYTES = 2 * 1024;

    public DownloadScoreBreakdown score(DownloadSearchCriteria criteria, NormalizedDownloadResult result) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        boolean directUrlMatch = directUrlMatches(criteria, result);

        if (requiresDownloadUrl(result) && isBlank(result.getDownloadUrl())) {
            score -= 100;
            reasons.add("-100 missing download URL");
        }

        if (directUrlMatch) {
            score += 80;
            reasons.add("+80 direct URL exact match");
        }

        if (!isBlank(criteria.getIsbn()) && !isBlank(result.getIsbn())) {
            if (cleanIsbn(criteria.getIsbn()).equals(cleanIsbn(result.getIsbn()))) {
                score += 100;
                reasons.add("+100 ISBN exact match");
            } else {
                score -= 40;
                reasons.add("-40 ISBN mismatch");
            }
        }

        String expectedTitle = firstNonBlank(criteria.getTitle(), criteria.effectiveQuery());
        boolean skipTitleScoringForAuthorQuery = shouldSkipTitleScoringForAuthorOnlyQuery(criteria, result);
        if (skipTitleScoringForAuthorQuery) {
            reasons.add("title scoring skipped for author query");
        } else if (!isBlank(expectedTitle) && (!isBlank(result.getTitle()) || !isBlank(result.getSeriesName()))) {
            double titleSimilarity = Math.max(similarity(expectedTitle, result.getTitle()), similarity(expectedTitle, result.getSeriesName()));
            if (queryContainsMoreThanAuthor(expectedTitle, result)) {
                titleSimilarity = Math.max(titleSimilarity, similarity(expectedTitle, combinedTitleAndAuthors(result)));
            }
            if (titleSimilarity >= 0.98) {
                score += 45;
                reasons.add("+45 title exact match");
            } else if (titleSimilarity >= 0.85) {
                score += 35;
                reasons.add("+35 title strong match");
            } else if (titleSimilarity >= 0.65) {
                score += 20;
                reasons.add("+20 title partial match");
            } else {
                score -= 35;
                reasons.add("-35 title weak match");
            }
            score += scoreRequestedNumber(criteria, expectedTitle, result, reasons);
        }

        score += scoreUnsupportedMedia(result, reasons);

        if (!isBlank(criteria.getAuthor()) && result.getAuthors() != null && !result.getAuthors().isEmpty()) {
            double bestAuthor = result.getAuthors().stream()
                    .mapToDouble(author -> similarity(criteria.getAuthor(), author))
                    .max()
                    .orElse(0);
            if (bestAuthor >= 0.90) {
                score += 25;
                reasons.add("+25 author match");
            } else if (bestAuthor >= 0.65) {
                score += 10;
                reasons.add("+10 author partial match");
            } else {
                score -= 20;
                reasons.add("-20 author mismatch");
            }
        } else if (isBlank(criteria.getAuthor()) && !isBlank(criteria.effectiveQuery()) && result.getAuthors() != null && !result.getAuthors().isEmpty()) {
            double bestAuthor = result.getAuthors().stream()
                    .mapToDouble(author -> similarity(criteria.effectiveQuery(), author))
                    .max()
                    .orElse(0);
            if (bestAuthor >= 0.85) {
                score += 45;
                reasons.add("+45 query author match");
            } else if (bestAuthor >= 0.65) {
                score += 20;
                reasons.add("+20 query author partial match");
            }
        }

        if (!isBlank(criteria.getSeriesName()) && !isBlank(result.getSeriesName())) {
            double seriesSimilarity = similarity(criteria.getSeriesName(), result.getSeriesName());
            if (seriesSimilarity >= 0.90) {
                score += 20;
                reasons.add("+20 series match");
            } else if (seriesSimilarity >= 0.65) {
                score += 8;
                reasons.add("+8 series partial match");
            } else {
                score -= 20;
                reasons.add("-20 series mismatch");
            }
        }

        if (criteria.getSeriesNumber() != null && result.getSeriesNumber() != null) {
            float delta = Math.abs(criteria.getSeriesNumber() - result.getSeriesNumber());
            if (delta < 0.01f) {
                score += 15;
                reasons.add("+15 series number exact match");
            } else if (delta <= 0.10f) {
                score += 6;
                reasons.add("+6 series number close match");
            } else {
                score -= 20;
                reasons.add("-20 series number mismatch");
            }
        }

        DownloadContentKind requestedKind = criteria.getContentKind() == null ? DownloadContentKind.AUTO : criteria.getContentKind();
        if (requestedKind == DownloadContentKind.AUTO) {
            score += 5;
            reasons.add("+5 content kind inferred automatically");
            if (result.getContentKind() != null && result.getContentKind().isSequentialArt()) {
                score += 5;
                reasons.add("+5 visual content kind evidence");
            }
        } else if (result.getContentKind() == requestedKind) {
            score += 10;
            reasons.add("+10 content kind match");
        } else if (directUrlMatch && requestedKind == DownloadContentKind.BOOK) {
            score += 10;
            reasons.add("+10 content kind inferred from direct URL");
        } else {
            score -= 30;
            reasons.add("-30 content kind mismatch");
        }

        score += scoreFormat(criteria.getPreferredFormats(), result, reasons);

        if (result.getSizeBytes() != null) {
            if (result.getSizeBytes() >= MIN_REASONABLE_SIZE_BYTES) {
                score += 5;
                reasons.add("+5 non-empty size");
            } else {
                score -= 25;
                reasons.add("-25 suspiciously small file");
            }
        }

        score += scoreWebtoonEpisodeSource(criteria, result, reasons);
        score += scoreWebtoonEpisodeTitleTieBreaker(criteria, result, reasons);

        int clamped = Math.max(0, Math.min(100, score));
        if (clamped != score) {
            reasons.add("clamped to " + clamped);
        }
        return DownloadScoreBreakdown.builder()
                .score(clamped)
                .reasons(reasons)
                .build();
    }

    private int scoreFormat(List<DownloadFormat> preferredFormats, NormalizedDownloadResult result, List<String> reasons) {
        DownloadFormat resultFormat = result.getFormat();
        if (resultFormat == null || resultFormat == DownloadFormat.UNKNOWN) {
            if (isDeferredSequentialArtPayload(preferredFormats, result)) {
                reasons.add("+5 torrent payload format deferred");
                return 5;
            }
            reasons.add("-15 unknown format");
            return -15;
        }
        if (preferredFormats == null || preferredFormats.isEmpty()) {
            reasons.add("+5 recognized format");
            return 5;
        }
        if (preferredFormats.contains(resultFormat)) {
            reasons.add("+20 preferred format");
            return 20;
        }
        reasons.add("-50 wrong format");
        return -50;
    }

    private boolean isDeferredSequentialArtPayload(List<DownloadFormat> preferredFormats, NormalizedDownloadResult result) {
        if (result.getContentKind() == null || !result.getContentKind().isSequentialArt()) {
            return false;
        }
        DownloadAcquisitionType acquisitionType = result.getAcquisitionType();
        if (acquisitionType != DownloadAcquisitionType.TORRENT && acquisitionType != DownloadAcquisitionType.NZB) {
            return false;
        }
        if (preferredFormats == null || preferredFormats.isEmpty()) {
            return true;
        }
        return preferredFormats.stream().anyMatch(format -> format != null && format.isArchiveComicFormat());
    }

    private int scoreWebtoonEpisodeSource(DownloadSearchCriteria criteria, NormalizedDownloadResult result, List<String> reasons) {
        if (criteria == null || result == null) {
            return 0;
        }
        DownloadContentKind requestedKind = criteria.getContentKind() == null ? DownloadContentKind.AUTO : criteria.getContentKind();
        if (requestedKind != DownloadContentKind.WEBTOON
                || result.getContentKind() != DownloadContentKind.WEBTOON
                || !hasRequestedSequentialNumber(criteria)
                || result.getAcquisitionType() != DownloadAcquisitionType.EXTERNAL_STACKS) {
            return 0;
        }
        reasons.add("-60 non-native webtoon episode source");
        return -60;
    }

    private int scoreWebtoonEpisodeTitleTieBreaker(DownloadSearchCriteria criteria, NormalizedDownloadResult result, List<String> reasons) {
        if (criteria == null || result == null) {
            return 0;
        }
        DownloadContentKind requestedKind = criteria.getContentKind() == null ? DownloadContentKind.AUTO : criteria.getContentKind();
        if (requestedKind != DownloadContentKind.WEBTOON
                || result.getContentKind() != DownloadContentKind.WEBTOON
                || result.getAcquisitionType() != DownloadAcquisitionType.CLI_GALLERY_DL) {
            return 0;
        }
        OptionalInt requestedEpisode = requestedWebtoonEpisode(criteria);
        if (requestedEpisode.isEmpty() || resultTitleHasWebtoonEpisodeMarker(result, requestedEpisode.getAsInt())) {
            return 0;
        }
        reasons.add("-50 explicit episode marker missing from result title");
        return -50;
    }

    private boolean hasRequestedSequentialNumber(DownloadSearchCriteria criteria) {
        if (criteria.getSeriesNumber() != null) {
            return true;
        }
        String evidence = String.join(" ", safe(criteria.getQuery()), safe(criteria.getTitle()), safe(criteria.getSeriesName()));
        return trailingNumber(evidence).isPresent() || hasAnyNumberMarker(evidence);
    }

    private OptionalInt requestedWebtoonEpisode(DownloadSearchCriteria criteria) {
        String evidence = String.join(" ", safe(criteria.getQuery()), safe(criteria.getTitle()));
        var matcher = EXPLICIT_WEBTOON_EPISODE_MARKER.matcher(evidence);
        if (matcher.find()) {
            String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return OptionalInt.of(Integer.parseInt(number));
        }
        if (criteria.getSeriesNumber() != null) {
            return OptionalInt.of(Math.round(criteria.getSeriesNumber()));
        }
        return OptionalInt.empty();
    }

    private boolean resultTitleHasWebtoonEpisodeMarker(NormalizedDownloadResult result, int requestedEpisode) {
        String title = safe(result.getTitle());
        var matcher = EXPLICIT_WEBTOON_EPISODE_MARKER.matcher(title);
        while (matcher.find()) {
            String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (Integer.parseInt(number) == requestedEpisode) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresDownloadUrl(NormalizedDownloadResult result) {
        return result == null
                || result.getAcquisitionType() == null
                || result.getAcquisitionType() != org.booklore.model.enums.DownloadAcquisitionType.EXTERNAL_STACKS;
    }

    private boolean queryContainsMoreThanAuthor(String query, NormalizedDownloadResult result) {
        if (isBlank(query) || result.getAuthors() == null || result.getAuthors().isEmpty()) {
            return false;
        }
        Set<String> queryTokens = tokens(normalize(query));
        if (queryTokens.isEmpty()) {
            return false;
        }
        Set<String> authorTokens = new LinkedHashSet<>();
        for (String author : result.getAuthors()) {
            authorTokens.addAll(tokens(normalize(author)));
        }
        authorTokens.remove("");
        if (hasMeaningfulTitleTokenOverlap(query, result)) {
            return true;
        }
        return !authorTokens.isEmpty() && !authorTokens.containsAll(queryTokens);
    }

    private boolean shouldSkipTitleScoringForAuthorOnlyQuery(DownloadSearchCriteria criteria, NormalizedDownloadResult result) {
        if (criteria == null
                || result == null
                || !isBlank(criteria.getTitle())
                || !isBlank(criteria.getAuthor())
                || !isBlank(criteria.getSeriesName())
                || !isBlank(criteria.getIsbn())
                || criteria.getSeriesNumber() != null
                || isBlank(criteria.effectiveQuery())
                || result.getAuthors() == null
                || result.getAuthors().isEmpty()) {
            return false;
        }

        Set<String> queryTokens = tokens(normalize(criteria.effectiveQuery()));
        if (queryTokens.isEmpty()) {
            return false;
        }
        double titleSimilarity = Math.max(similarity(criteria.effectiveQuery(), result.getTitle()), similarity(criteria.effectiveQuery(), result.getSeriesName()));
        if (titleSimilarity >= 0.65D || hasMeaningfulTitleTokenOverlap(criteria.effectiveQuery(), result)) {
            return false;
        }

        for (String author : result.getAuthors()) {
            Set<String> authorTokens = tokens(normalize(author));
            if (!authorTokens.isEmpty() && authorTokens.containsAll(queryTokens)) {
                return true;
            }
            if (similarity(criteria.effectiveQuery(), author) >= 0.85D) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMeaningfulTitleTokenOverlap(String query, NormalizedDownloadResult result) {
        Set<String> queryTokens = significantTokens(query);
        if (queryTokens.isEmpty()) {
            return false;
        }
        Set<String> titleTokens = significantTokens(String.join(" ", safe(result.getTitle()), safe(result.getSeriesName())));
        if (titleTokens.isEmpty()) {
            return false;
        }
        int overlap = 0;
        for (String token : queryTokens) {
            if (titleTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap >= Math.min(2, queryTokens.size());
    }

    private Set<String> significantTokens(String value) {
        Set<String> values = new LinkedHashSet<>(tokens(normalize(value)));
        values.removeIf(token -> token.length() < 3);
        return values;
    }

    private String combinedTitleAndAuthors(NormalizedDownloadResult result) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(result.getTitle())) {
            parts.add(result.getTitle());
        }
        if (!isBlank(result.getSeriesName())) {
            parts.add(result.getSeriesName());
        }
        if (result.getAuthors() != null) {
            parts.addAll(result.getAuthors());
        }
        return String.join(" ", parts);
    }

    private int scoreRequestedNumber(DownloadSearchCriteria criteria, String expectedTitle, NormalizedDownloadResult result, List<String> reasons) {
        OptionalInt requestedNumber = criteria.getSeriesNumber() == null
                ? trailingNumber(expectedTitle)
                : OptionalInt.of(Math.round(criteria.getSeriesNumber()));
        if (requestedNumber.isEmpty() || (isBlank(result.getTitle()) && isBlank(result.getSeriesName()))) {
            return 0;
        }

        int number = requestedNumber.getAsInt();
        String evidence = String.join(" ", safe(result.getTitle()), safe(result.getSeriesName()));
        DownloadSequenceNumberType requestedSequenceType = criteria.getSequenceNumberType() == null
                ? DownloadSequenceNumberType.AUTO
                : criteria.getSequenceNumberType();

        if (hasRangeContaining(evidence, number)) {
            if (requestedSequenceType.isChapterLike()) {
                reasons.add("-65 bundled range cannot satisfy requested " + sequenceNumberLabel(requestedSequenceType) + " exactly");
                return -65;
            }
            if (requestedSequenceType.isVolumeLike()) {
                reasons.add("-45 bundled range cannot satisfy requested " + sequenceNumberLabel(requestedSequenceType) + " exactly");
                return -45;
            }
            reasons.add("-5 bundled range contains requested number");
            return -5;
        }
        if (hasExactNumberMarker(evidence, number, requestedSequenceType)) {
            reasons.add("+20 requested " + sequenceNumberLabel(requestedSequenceType) + " number match");
            return 20;
        }
        if (hasConflictingNumberMarker(evidence, number, requestedSequenceType)) {
            reasons.add("-60 conflicting " + conflictingSequenceNumberLabel(requestedSequenceType) + " marker for requested " + sequenceNumberLabel(requestedSequenceType));
            return -60;
        }
        if (result.getSeriesNumber() != null && result.getContentKind() != null && result.getContentKind().isSequentialArt()) {
            if (requestedSequenceType.isVolumeLike() && isChapterEpisodeSource(result)) {
                reasons.add("-85 chapter/episode result for volume/issue request");
                return -85;
            }
            if (matchesSeriesNumber(result.getSeriesNumber(), number)) {
                reasons.add("+10 requested " + sequenceNumberLabel(requestedSequenceType) + " number match");
                return 10;
            }
            int penalty = requestedSequenceType.isChapterLike() ? -65 : -20;
            reasons.add(penalty + " requested " + sequenceNumberLabel(requestedSequenceType) + " number mismatch");
            return penalty;
        }
        if (hasLooseNumberToken(evidence, number)) {
            reasons.add("+5 requested number token present");
            return 5;
        }
        if (result.getContentKind() != null && result.getContentKind().isSequentialArt() && hasAnyNumberMarker(evidence)) {
            int penalty = requestedSequenceType.isChapterLike() ? -65 : -20;
            reasons.add(penalty + " requested " + sequenceNumberLabel(requestedSequenceType) + " number mismatch");
            return penalty;
        }
        if (result.getContentKind() != null && result.getContentKind().isSequentialArt()) {
            int penalty = requestedSequenceType.isChapterLike() ? -65 : -20;
            reasons.add(penalty + " missing requested " + sequenceNumberLabel(requestedSequenceType) + " number");
            return penalty;
        }
        return 0;
    }

    private boolean matchesSeriesNumber(Float seriesNumber, int requestedNumber) {
        if (seriesNumber == null) {
            return false;
        }
        return Math.abs(seriesNumber - requestedNumber) < 0.01f;
    }

    private boolean isChapterEpisodeSource(NormalizedDownloadResult result) {
        DownloadAcquisitionType acquisitionType = result.getAcquisitionType();
        return acquisitionType == DownloadAcquisitionType.MANGADEX_CHAPTER
                || acquisitionType == DownloadAcquisitionType.KAGANE_CHAPTER
                || acquisitionType == DownloadAcquisitionType.CLI_GALLERY_DL;
    }

    private OptionalInt trailingNumber(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return OptionalInt.empty();
        }
        String[] tokens = normalized.split(" ");
        String last = tokens[tokens.length - 1];
        if (!last.matches("\\d{1,5}")) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(last));
    }

    private boolean hasExactNumberMarker(String title, int number, DownloadSequenceNumberType requestedSequenceType) {
        String numberPattern = "0*" + number + "\\b(?!\\s*[.]\\s*\\d)";
        String markerPattern = switch (requestedSequenceType) {
            case VOLUME -> "(?iu)\\b(?:vol(?:ume)?|v|t(?:ome|omo)?)\\.?\\s*" + numberPattern;
            case ISSUE -> "(?iu)(?:\\b(?:issue|iss)\\.?\\s*" + numberPattern + "|#\\s*" + numberPattern + ")";
            case CHAPTER, EPISODE -> "(?iu)(?:\\b(?:ch(?:apter)?|chapitre|episode|ep)\\.?\\s*" + numberPattern + "|#\\s*" + numberPattern + ")";
            case AUTO -> "(?iu)(?:\\b(?:vol(?:ume)?|v|t(?:ome|omo)?|ch(?:apter)?|chapitre|episode|ep|issue|iss)\\.?\\s*" + numberPattern + "|#\\s*" + numberPattern + ")";
        };
        return Pattern.compile(markerPattern).matcher(title).find();
    }

    private boolean hasConflictingNumberMarker(String title, int number, DownloadSequenceNumberType requestedSequenceType) {
        String markerPattern = switch (requestedSequenceType) {
            case CHAPTER, EPISODE -> "(?iu)\\b(?:vol(?:ume)?|v|t(?:ome|omo)?|issue|iss)\\.?\\s*0*" + number + "\\b";
            case VOLUME, ISSUE -> "(?iu)(?:\\b(?:ch(?:apter)?|chapitre|episode|ep)\\.?\\s*0*" + number + "\\b|#\\s*0*" + number + "\\b)";
            case AUTO -> null;
        };
        return markerPattern != null && Pattern.compile(markerPattern).matcher(title).find();
    }

    private boolean hasRangeContaining(String title, int number) {
        if (markedRangeContains(SEQUENTIAL_MARKED_RANGE.matcher(title), number)) {
            return true;
        }
        var matcher = NUMBER_RANGE.matcher(title);
        return markedRangeContains(matcher, number);
    }

    private boolean markedRangeContains(java.util.regex.Matcher matcher, int number) {
        while (matcher.find()) {
            int start = Integer.parseInt(matcher.group(1));
            int end = Integer.parseInt(matcher.group(2));
            if (Math.min(start, end) <= number && number <= Math.max(start, end)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLooseNumberToken(String title, int number) {
        String pattern = "(?<![\\d./\\\\])0*" + number + "(?![\\d./\\\\])";
        return Pattern.compile(pattern).matcher(title).find();
    }

    private boolean hasAnyNumberMarker(String title) {
        return ANY_NUMBER_MARKER.matcher(title).find();
    }

    private String sequenceNumberLabel(DownloadSequenceNumberType type) {
        return switch (type) {
            case VOLUME -> "volume";
            case ISSUE -> "issue";
            case CHAPTER -> "chapter";
            case EPISODE -> "episode";
            case AUTO -> "volume/chapter";
        };
    }

    private String conflictingSequenceNumberLabel(DownloadSequenceNumberType type) {
        return switch (type) {
            case CHAPTER, EPISODE -> "volume/issue";
            case VOLUME, ISSUE -> "chapter/episode";
            case AUTO -> "sequence";
        };
    }

    private int scoreUnsupportedMedia(NormalizedDownloadResult result, List<String> reasons) {
        if (!looksLikeUnsupportedMedia(result)) {
            return 0;
        }
        reasons.add("-90 unsupported media payload");
        return -90;
    }

    private boolean looksLikeUnsupportedMedia(NormalizedDownloadResult result) {
        DownloadAcquisitionType acquisitionType = result.getAcquisitionType();
        boolean externalPayload = acquisitionType == DownloadAcquisitionType.TORRENT || acquisitionType == DownloadAcquisitionType.NZB;
        boolean unknownFormat = result.getFormat() == null || result.getFormat() == DownloadFormat.UNKNOWN;
        if (!externalPayload && !unknownFormat) {
            return false;
        }
        String evidence = normalize(String.join(" ",
                safe(result.getTitle()),
                safe(result.getSeriesName()),
                safe(result.getDownloadUrl()),
                safe(result.getDetailsUrl()),
                safe(result.getRawJson())
        ));
        return UNSUPPORTED_MEDIA_MARKER.matcher(evidence).find();
    }

    private double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) return 0;
        if (a.equals(b)) return 1;
        if (a.contains(b) || b.contains(a)) return 0.88;

        Set<String> leftTokens = tokens(a);
        Set<String> rightTokens = tokens(b);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0;

        if (leftTokens.size() >= 2 && rightTokens.containsAll(leftTokens)) {
            double coverage = (double) leftTokens.size() / (double) rightTokens.size();
            if (hasNumericToken(leftTokens) || coverage >= 0.50D) {
                return 0.86;
            }
            if (leftTokens.size() >= 4 && coverage >= 0.30D) {
                return 0.86;
            }
        }

        return jaccard(leftTokens, rightTokens);
    }

    private boolean hasNumericToken(Set<String> tokens) {
        return tokens.stream().anyMatch(token -> token.matches("\\d{1,5}"));
    }

    private double jaccard(Set<String> leftTokens, Set<String> rightTokens) {
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / (double) union.size();
    }

    private Set<String> tokens(String normalized) {
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
        tokens.remove("");
        return tokens;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        normalized = COMPACT_NUMBER_MARKER.matcher(normalized).replaceAll("$1 $2");
        return NON_ALNUM.matcher(normalized).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private String cleanIsbn(String value) {
        return value == null ? "" : value.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private boolean directUrlMatches(DownloadSearchCriteria criteria, NormalizedDownloadResult result) {
        if (criteria == null || isBlank(criteria.getDirectUrl()) || result == null) {
            return false;
        }
        String directUrl = normalizeUrlForComparison(criteria.getDirectUrl());
        return directUrl.equals(normalizeUrlForComparison(result.getDownloadUrl()))
                || directUrl.equals(normalizeUrlForComparison(result.getDetailsUrl()));
    }

    private String normalizeUrlForComparison(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
