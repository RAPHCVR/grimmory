package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
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
        if (!isBlank(expectedTitle) && (!isBlank(result.getTitle()) || !isBlank(result.getSeriesName()))) {
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
            score += scoreRequestedNumber(expectedTitle, result, reasons);
        }

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
        return !authorTokens.isEmpty() && !authorTokens.containsAll(queryTokens);
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

    private int scoreRequestedNumber(String expectedTitle, NormalizedDownloadResult result, List<String> reasons) {
        OptionalInt requestedNumber = trailingNumber(expectedTitle);
        if (requestedNumber.isEmpty() || isBlank(result.getTitle())) {
            return 0;
        }

        int number = requestedNumber.getAsInt();
        String title = result.getTitle();
        if (hasExactNumberMarker(title, number)) {
            reasons.add("+20 requested volume/chapter number match");
            return 20;
        }
        if (hasRangeContaining(title, number)) {
            reasons.add("-5 bundled range contains requested number");
            return -5;
        }
        if (hasNumberToken(title, number)) {
            reasons.add("+5 requested number token present");
            return 5;
        }
        return 0;
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

    private boolean hasExactNumberMarker(String title, int number) {
        String markerPattern = "(?iu)(?:\\bvol(?:ume)?\\b|\\bv\\b|\\btome\\b|\\bch(?:apter)?\\b|\\bchapitre\\b|#)\\s*\\.?\\s*0*" + number + "\\b";
        return Pattern.compile(markerPattern).matcher(title).find();
    }

    private boolean hasRangeContaining(String title, int number) {
        var matcher = NUMBER_RANGE.matcher(title);
        while (matcher.find()) {
            int start = Integer.parseInt(matcher.group(1));
            int end = Integer.parseInt(matcher.group(2));
            if (Math.min(start, end) <= number && number <= Math.max(start, end)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNumberToken(String title, int number) {
        return tokens(normalize(title)).stream()
                .filter(token -> token.matches("\\d{1,5}"))
                .mapToInt(Integer::parseInt)
                .anyMatch(value -> value == number);
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
}
