package org.booklore.model.dto.request.downloads;

import lombok.Data;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;

import java.util.List;

@Data
public class DownloadSearchRequest {
    private String query;
    private String title;
    private String author;
    private String isbn;
    private String seriesName;
    private Float seriesNumber;
    private DownloadContentKind contentKind = DownloadContentKind.BOOK;
    private List<DownloadFormat> preferredFormats = List.of();
    private String directUrl;
    private Integer maxResults = 25;
}
