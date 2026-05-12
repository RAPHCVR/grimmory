package org.booklore.service.downloads.executor;

import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DownloadExecutorRegistry {

    private final List<DownloadExecutor> executors;

    public DownloadExecutorRegistry(List<DownloadExecutor> executors) {
        this.executors = List.copyOf(executors);
    }

    public DownloadExecutor executorFor(DownloadAcquisitionType acquisitionType) {
        return executors.stream()
                .filter(executor -> executor.supports(acquisitionType))
                .findFirst()
                .orElseThrow(() -> new DownloadSourceException("No download executor registered for acquisition type " + acquisitionType));
    }
}
