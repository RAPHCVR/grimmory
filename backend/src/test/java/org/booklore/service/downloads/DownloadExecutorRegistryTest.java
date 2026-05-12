package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.DownloadExecutor;
import org.booklore.service.downloads.executor.DownloadExecutorRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DownloadExecutorRegistryTest {

    @Test
    void executorFor_knownType_returnsMatchingExecutor() {
        DownloadExecutor directExecutor = executorFor(DownloadAcquisitionType.DIRECT_FILE);
        DownloadExecutorRegistry registry = new DownloadExecutorRegistry(List.of(directExecutor));

        assertSame(directExecutor, registry.executorFor(DownloadAcquisitionType.DIRECT_FILE));
    }

    @Test
    void executorFor_unknownType_throwsDownloadSourceException() {
        DownloadExecutorRegistry registry = new DownloadExecutorRegistry(List.of(executorFor(DownloadAcquisitionType.DIRECT_FILE)));

        assertThrows(DownloadSourceException.class, () -> registry.executorFor(DownloadAcquisitionType.TORRENT));
    }

    private DownloadExecutor executorFor(DownloadAcquisitionType type) {
        return new DownloadExecutor() {
            @Override
            public boolean supports(DownloadAcquisitionType acquisitionType) {
                return acquisitionType == type;
            }

            @Override
            public Path download(DownloadExecutionRequest request, DownloadProgressSink progressSink) {
                return request.getTargetPartFile();
            }
        };
    }
}
