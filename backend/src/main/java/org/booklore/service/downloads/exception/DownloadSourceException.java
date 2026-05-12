package org.booklore.service.downloads.exception;

public class DownloadSourceException extends DownloadException {
    public DownloadSourceException(String message) {
        super(message);
    }

    public DownloadSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
