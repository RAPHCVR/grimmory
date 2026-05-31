package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSequenceNumberType;
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
            "(?iu)\\b(vol(?:ume)?|v|t(?:ome|omo)?|issue|iss|ch(?:apter)?|chap(?:itre)?|chapter|episode|ep)\\.?\\s*0*(\\d{1,5}(?:\\.\\d+)?)\\b|#\\s*0*(\\d{1,5}(?:\\.\\d+)?)\\b"
    );
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?iu)^(.+?)\\s+0*(\\d{1,5}(?:\\.\\d+)?)$");
    private static final Pattern DANGLING_SEPARATORS = Pattern.compile("(?iu)[\\s,;:_\\-–—#]+$|^[\\s,;:_\\-–—#]+");
    private static final Pattern MULTISPACE = Pattern.compile("\\s+");

    public DownloadSearchCriteria enrich(DownloadSearchCriteria criteria) {
        if (criteria == null || !isSequentialSearch(criteria)) {
            return criteria;
        }

        String source = firstNonBlank(criteria.getQuery(), criteria.getTitle(), criteria.getSeriesName());
        if (source == null && criteria.getSeriesNumber() == null) {
            return criteria;
        }

        Optional<ParsedNumberIntent> intent = parse(source, criteria.getContentKind());
        if (intent.isEmpty()) {
            return criteria;
        }

        ParsedNumberIntent parsed = intent.get();
        DownloadSearchCriteria.DownloadSearchCriteriaBuilder builder = criteria.toBuilder();
        if (criteria.getSeriesNumber() == null) {
            builder.seriesNumber(parsed.number());
        }
        if (criteria.getSequenceNumberType() == null || criteria.getSequenceNumberType().isAuto()) {
            builder.sequenceNumberType(parsed.sequenceNumberType());
        }
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
        return parse(value, DownloadContentKind.AUTO);
    }

    Optional<ParsedNumberIntent> parse(String value, DownloadContentKind contentKind) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        Matcher explicit = EXPLICIT_NUMBER_MARKER.matcher(value);
        ParsedNumberIntent best = null;
        while (explicit.find()) {
            String marker = explicit.group(1);
            String numberText = explicit.group(2) != null ? explicit.group(2) : explicit.group(3);
            Float number = parseNumber(numberText);
            if (number == null || looksLikeYear(number)) {
                continue;
            }
            String cleanTitle = cleanTitle(explicit.replaceFirst(" "));
            if (!cleanTitle.isBlank()) {
                best = new ParsedNumberIntent(cleanTitle, number, sequenceTypeForExplicitMarker(marker, contentKind));
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
        return cleanTitle.isBlank() ? Optional.empty() : Optional.of(new ParsedNumberIntent(cleanTitle, number, sequenceTypeForTrailingNumber(contentKind)));
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

    private DownloadSequenceNumberType sequenceTypeForExplicitMarker(String marker, DownloadContentKind contentKind) {
        if (marker == null || marker.isBlank()) {
            return sequenceTypeForHashMarker(contentKind);
        }
        String normalized = marker.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("vol") || normalized.equals("v") || normalized.startsWith("tome") || normalized.startsWith("tomo")) {
            return DownloadSequenceNumberType.VOLUME;
        }
        if (normalized.startsWith("issue") || normalized.startsWith("iss")) {
            return DownloadSequenceNumberType.ISSUE;
        }
        if (normalized.startsWith("ep")) {
            return DownloadSequenceNumberType.EPISODE;
        }
        return contentKind == DownloadContentKind.WEBTOON ? DownloadSequenceNumberType.EPISODE : DownloadSequenceNumberType.CHAPTER;
    }

    private DownloadSequenceNumberType sequenceTypeForTrailingNumber(DownloadContentKind contentKind) {
        if (contentKind == DownloadContentKind.WEBTOON) {
            return DownloadSequenceNumberType.EPISODE;
        }
        if (contentKind == DownloadContentKind.COMIC) {
            return DownloadSequenceNumberType.ISSUE;
        }
        return DownloadSequenceNumberType.VOLUME;
    }

    private DownloadSequenceNumberType sequenceTypeForHashMarker(DownloadContentKind contentKind) {
        if (contentKind == DownloadContentKind.WEBTOON) {
            return DownloadSequenceNumberType.EPISODE;
        }
        if (contentKind == DownloadContentKind.COMIC) {
            return DownloadSequenceNumberType.ISSUE;
        }
        return DownloadSequenceNumberType.VOLUME;
    }

    record ParsedNumberIntent(String cleanTitle, Float number, DownloadSequenceNumberType sequenceNumberType) {
        @Override
        public String toString() {
            return cleanTitle + " " + sequenceNumberType + " " + String.format(Locale.ROOT, "%.2f", number);
        }
    }
}
