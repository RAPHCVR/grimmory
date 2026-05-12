package org.booklore.service.downloads.dto;

import lombok.Builder;
import lombok.Value;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;

import java.util.List;

@Value
@Builder(toBuilder = true)
public class NormalizedDownloadResult {
    String sourceResultId;
    String title;
    @Builder.Default
    List<String> authors = List.of();
    String seriesName;
    Float seriesNumber;
    Integer publishedYear;
    String isbn;
    String language;
    @Builder.Default
    DownloadFormat format = DownloadFormat.UNKNOWN;
    @Builder.Default
    DownloadContentKind contentKind = DownloadContentKind.BOOK;
    @Builder.Default
    DownloadAcquisitionType acquisitionType = DownloadAcquisitionType.UNKNOWN;
    Long sizeBytes;
    String downloadUrl;
    String detailsUrl;
    @Builder.Default
    boolean requiresFlareSolverr = false;
    String rawJson;

    public String primaryAuthor() {
        return authors == null || authors.isEmpty() ? null : authors.getFirst();
    }
}
