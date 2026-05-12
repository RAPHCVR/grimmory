package org.booklore.service.downloads.executor;

import lombok.Builder;
import lombok.Value;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;

import java.nio.file.Path;

@Value
@Builder
public class DownloadExecutionRequest {
    DownloadJobEntity job;
    DownloadSourceEntity source;
    NormalizedDownloadResult result;
    Path stagingDir;
    Path targetPartFile;
}
