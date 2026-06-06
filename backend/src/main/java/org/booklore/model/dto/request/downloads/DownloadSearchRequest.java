package org.booklore.model.dto.request.downloads;

import lombok.Data;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSequenceNumberType;

import java.util.List;

@Data
public class DownloadSearchRequest {
    private String query;
    private String title;
    private String author;
    private String isbn;
    private String seriesName;
    private Float seriesNumber;
    private Float seriesNumberEnd;
    private String preferredLanguage;
    private DownloadSequenceNumberType sequenceNumberType = DownloadSequenceNumberType.AUTO;
    private DownloadContentKind contentKind = DownloadContentKind.AUTO;
    private List<DownloadFormat> preferredFormats = List.of();
    private String directUrl;
    private DownloadCanonicalSelectionRequest canonicalSelection;
    private Integer maxResults = 25;
}
