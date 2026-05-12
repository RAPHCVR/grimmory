package org.booklore.model.dto.request.downloads;

import lombok.Data;

@Data
public class DownloadResultAcquireRequest {
    private Long targetLibraryId;
    private Long targetLibraryPathId;
    private Boolean autoFinalize = true;
    private Integer confidenceThreshold = 90;
}
