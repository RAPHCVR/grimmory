package org.booklore.service.downloads.dto;

@FunctionalInterface
public interface DownloadProgressSink {
    void onProgress(int percent);
}
