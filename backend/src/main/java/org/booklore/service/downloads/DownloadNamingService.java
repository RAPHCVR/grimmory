package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.util.PathPatternResolver;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class DownloadNamingService {

    private static final Pattern INVALID_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String buildFinalFileName(NormalizedDownloadResult result, DownloadFormat detectedFormat) {
        String extension = detectedFormat.extension();
        String fileName = switch (result.getContentKind()) {
            case MANGA, COMIC -> mangaName(result, extension);
            case WEBTOON -> webtoonName(result, extension);
            case BOOK -> bookName(result, extension);
        };
        return PathPatternResolver.truncateFilenameWithExtension(sanitize(fileName));
    }

    private String bookName(NormalizedDownloadResult result, String extension) {
        String author = valueOrDefault(result.primaryAuthor(), "Unknown Author");
        String title = valueOrDefault(result.getTitle(), "Untitled");
        String year = result.getPublishedYear() == null ? "" : " (" + result.getPublishedYear() + ")";
        String isbn = result.getIsbn() == null || result.getIsbn().isBlank() ? "" : " [" + result.getIsbn() + "]";
        return author + " - " + title + year + isbn + "." + extension;
    }

    private String mangaName(NormalizedDownloadResult result, String extension) {
        String series = valueOrDefault(result.getSeriesName(), result.getTitle());
        String number = result.getSeriesNumber() == null ? "" : " - v" + formatVolume(result.getSeriesNumber());
        String title = result.getTitle() == null || result.getTitle().equalsIgnoreCase(series) ? "" : " - " + result.getTitle();
        return series + number + title + "." + extension;
    }

    private String webtoonName(NormalizedDownloadResult result, String extension) {
        String series = valueOrDefault(result.getSeriesName(), result.getTitle());
        String number = result.getSeriesNumber() == null ? "" : " - Ch " + formatChapter(result.getSeriesNumber());
        String title = result.getTitle() == null || result.getTitle().equalsIgnoreCase(series) ? "" : " - " + result.getTitle();
        return series + number + title + "." + extension;
    }

    private String formatVolume(Float value) {
        if (value % 1 == 0) {
            return String.format(Locale.ROOT, "%02d", value.intValue());
        }
        int intPart = value.intValue();
        String raw = value.toString();
        return String.format(Locale.ROOT, "%02d", intPart) + raw.substring(raw.indexOf('.'));
    }

    private String formatChapter(Float value) {
        if (value % 1 == 0) {
            return String.format(Locale.ROOT, "%03d", value.intValue());
        }
        int intPart = value.intValue();
        String raw = value.toString();
        return String.format(Locale.ROOT, "%03d", intPart) + raw.substring(raw.indexOf('.'));
    }

    private String sanitize(String input) {
        String cleaned = INVALID_CHARS.matcher(input).replaceAll("");
        return WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
