package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DownloadQueryIntentParser {

    private static final Pattern EXPLICIT_NUMBER_MARKER = Pattern.compile(
            "(?iu)(?:\\b(?:vol(?:ume)?|v|t(?:ome|omo)?|ch(?:apter)?|chap(?:itre)?|chapter|episode|ep)\\.?\\s*|#\\s*)0*(\\d{1,5}(?:\\.\\d+)?)\\b"
    );
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?iu)^(.+?)\\s+0*(\\d{1,5}(?:\\.\\d+)?)$");
    private static final Pattern DANGLING_SEPARATORS = Pattern.compile("(?iu)[\\s,;:_\\-–—#]+$|^[\\s,;:_\\-–—#]+");
    private static final Pattern MULTISPACE = Pattern.compile("\\s+");

    public DownloadSearchCriteria enrich(DownloadSearchCriteria criteria) {
        if (criteria == null || criteria.getSeriesNumber() != null || !isSequentialSearch(criteria)) {
            return criteria;
        }

        String source = firstNonBlank(criteria.getQuery(), criteria.getTitle(), criteria.getSeriesName());
        if (source == null) {
            return criteria;
        }

        Optional<ParsedNumberIntent> intent = parse(source);
        if (intent.isEmpty()) {
            return criteria;
        }

        ParsedNumberIntent parsed = intent.get();
        DownloadSearchCriteria.DownloadSearchCriteriaBuilder builder = criteria.toBuilder()
                .seriesNumber(parsed.number());
        if (criteria.getQuery() != null && !criteria.getQuery().isBlank()) {
            builder.query(parsed.cleanTitle());
        }
        if (criteria.getTitle() == null || criteria.getTitle().isBlank()) {
            builder.title(parsed.cleanTitle());
        }
        if ((criteria.getSeriesName() == null || criteria.getSeriesName().isBlank()) && hasConcreteSequentialKind(criteria)) {
            builder.seriesName(parsed.cleanTitle());
        }
        return builder.build();
    }

    Optional<ParsedNumberIntent> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        Matcher explicit = EXPLICIT_NUMBER_MARKER.matcher(value);
        ParsedNumberIntent best = null;
        while (explicit.find()) {
            Float number = parseNumber(explicit.group(1));
            if (number == null || looksLikeYear(number)) {
                continue;
            }
            String cleanTitle = cleanTitle(explicit.replaceFirst(" "));
            if (!cleanTitle.isBlank()) {
                best = new ParsedNumberIntent(cleanTitle, number);
            }
        }
        if (best != null) {
            return Optional.of(best);
        }

        Matcher trailing = TRAILING_NUMBER.matcher(value.trim());
        if (!trailing.matches()) {
            return Optional.empty();
        }
        Float number = parseNumber(trailing.group(2));
        if (number == null || looksLikeYear(number)) {
            return Optional.empty();
        }
        String cleanTitle = cleanTitle(trailing.group(1));
        return cleanTitle.isBlank() ? Optional.empty() : Optional.of(new ParsedNumberIntent(cleanTitle, number));
    }

    private boolean isSequentialSearch(DownloadSearchCriteria criteria) {
        if (hasConcreteSequentialKind(criteria)) {
            return true;
        }
        List<DownloadFormat> preferredFormats = criteria.getPreferredFormats();
        return preferredFormats != null && preferredFormats.stream()
                .anyMatch(format -> format != null && format.isArchiveComicFormat());
    }

    private boolean hasConcreteSequentialKind(DownloadSearchCriteria criteria) {
        DownloadContentKind contentKind = criteria.getContentKind();
        return contentKind != null && !contentKind.isAuto() && contentKind.isSequentialArt();
    }

    private Float parseNumber(String value) {
        try {
            return value == null || value.isBlank() ? null : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean looksLikeYear(Float number) {
        return number != null && number % 1 == 0 && number >= 1900 && number <= 2100;
    }

    private String cleanTitle(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("(?iu)\\b(?:volume|vol|tome|tomo|chapter|chapitre|chap|episode|ep)\\.?\\s*$", "")
                .trim();
        cleaned = DANGLING_SEPARATORS.matcher(cleaned).replaceAll("");
        return MULTISPACE.matcher(cleaned).replaceAll(" ").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    record ParsedNumberIntent(String cleanTitle, Float number) {
        @Override
        public String toString() {
            return cleanTitle + " #" + String.format(Locale.ROOT, "%.2f", number);
        }
    }
}
