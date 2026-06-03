package org.booklore.model.dto.request.downloads;

import lombok.Data;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadSequenceNumberType;

@Data
public class DownloadCanonicalSelectionRequest {
    private String provider;
    private DownloadContentKind contentKind = DownloadContentKind.AUTO;
    private String title;
    private String author;
    private String isbn;
    private String seriesName;
    private Double confidence;
    private String query;
    private String resolvedTitle;
    private String resolvedAuthor;
    private String resolvedIsbn;
    private String resolvedSeriesName;
    private Float seriesNumber;
    private DownloadSequenceNumberType sequenceNumberType = DownloadSequenceNumberType.AUTO;
    private String coverUrl;
}
