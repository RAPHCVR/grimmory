package org.booklore.service.downloads.executor;

import lombok.RequiredArgsConstructor;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.service.downloads.HttpDownloadClient;
import org.booklore.service.downloads.client.FlareSolverrClient;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HttpFileDownloadExecutor implements DownloadExecutor {

    private static final EnumSet<DownloadAcquisitionType> SUPPORTED = EnumSet.of(
            DownloadAcquisitionType.DIRECT_FILE,
            DownloadAcquisitionType.OPDS_ACQUISITION,
            DownloadAcquisitionType.WEB_PLUGIN
    );

    private final HttpDownloadClient httpDownloadClient;
    private final FlareSolverrClient flareSolverrClient;

    @Override
    public boolean supports(DownloadAcquisitionType acquisitionType) {
        return SUPPORTED.contains(acquisitionType);
    }

    @Override
    public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
        String url = request.getResult().getDownloadUrl();
        if (url == null || url.isBlank()) {
            throw new DownloadSourceException("Result does not expose a downloadable URL");
        }
        Map<String, String> headers = request.getResult().isRequiresFlareSolverr()
                ? flareSolverrClient.resolveHeaders(request.getSource(), url)
                : Map.of();
        return httpDownloadClient.downloadToFile(url, request.getTargetPartFile(), headers, progressSink);
    }
}
