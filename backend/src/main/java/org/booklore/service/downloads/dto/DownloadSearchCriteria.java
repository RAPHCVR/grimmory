package org.booklore.service.downloads.dto;

import lombok.Builder;
import lombok.Value;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSequenceNumberType;

import java.util.List;

@Value
@Builder(toBuilder = true)
public class DownloadSearchCriteria {
    String originalQuery;
    String query;
    String title;
    String author;
    String isbn;
    String seriesName;
    Float seriesNumber;
    @Builder.Default
    DownloadSequenceNumberType sequenceNumberType = DownloadSequenceNumberType.AUTO;
    @Builder.Default
    DownloadContentKind contentKind = DownloadContentKind.AUTO;
    @Builder.Default
    List<DownloadFormat> preferredFormats = List.of();
    String directUrl;
    CanonicalSelection canonicalSelection;
    @Builder.Default
    int maxResults = 25;

    public String effectiveQuery() {
        if (query != null && !query.isBlank()) return query;
        if (isbn != null && !isbn.isBlank()) return isbn;
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append(title);
        if (author != null && !author.isBlank()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(author);
        }
        if (seriesName != null && !seriesName.isBlank() && sb.isEmpty()) sb.append(seriesName);
        return sb.toString().trim();
    }

    public record CanonicalSelection(String provider,
                                     DownloadContentKind contentKind,
                                     String title,
                                     String author,
                                     String isbn,
                                     String seriesName,
                                     Double confidence,
                                     String query,
                                     String resolvedTitle,
                                     String resolvedAuthor,
                                     String resolvedIsbn,
                                     String resolvedSeriesName,
                                     Float seriesNumber,
                                     DownloadSequenceNumberType sequenceNumberType,
                                     String coverUrl) {
    }
}
