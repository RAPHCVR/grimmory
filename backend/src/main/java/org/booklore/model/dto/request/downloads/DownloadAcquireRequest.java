package org.booklore.model.dto.request.downloads;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DownloadAcquireRequest extends DownloadSearchRequest {
    private Long targetLibraryId;
    private Long targetLibraryPathId;
    private Boolean autoFinalize = false;
    private Integer confidenceThreshold = 90;
}
