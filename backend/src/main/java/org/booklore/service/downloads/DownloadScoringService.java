package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadFormat;
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
    private static final int MIN_REASONABLE_SIZE_BYTES = 2 * 1024;

    public DownloadScoreBreakdown score(DownloadSearchCriteria criteria, NormalizedDownloadResult result) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (isBlank(result.getDownloadUrl())) {
            score -= 100;
            reasons.add("-100 missing download URL");
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

        if (result.getContentKind() == criteria.getContentKind()) {
            score += 10;
            reasons.add("+10 content kind match");
        } else {
            score -= 30;
            reasons.add("-30 content kind mismatch");
        }

        score += scoreFormat(criteria.getPreferredFormats(), result.getFormat(), reasons);

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

    private int scoreFormat(List<DownloadFormat> preferredFormats, DownloadFormat resultFormat, List<String> reasons) {
        if (resultFormat == null || resultFormat == DownloadFormat.UNKNOWN) {
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

    private double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) return 0;
        if (a.equals(b)) return 1;
        if (a.contains(b) || b.contains(a)) return 0.88;

        Set<String> leftTokens = new LinkedHashSet<>(Arrays.asList(a.split(" ")));
        Set<String> rightTokens = new LinkedHashSet<>(Arrays.asList(b.split(" ")));
        leftTokens.remove("");
        rightTokens.remove("");
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0;

        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / (double) union.size();
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
