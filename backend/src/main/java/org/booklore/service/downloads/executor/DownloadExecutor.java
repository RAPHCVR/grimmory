package org.booklore.service.downloads.executor;

import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.service.downloads.dto.DownloadProgressSink;

import java.nio.file.Path;

public interface DownloadExecutor {

    boolean supports(DownloadAcquisitionType acquisitionType);

    Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink);
}
