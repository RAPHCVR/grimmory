package org.booklore.model.enums;

public enum DownloadJobStatus {
    QUEUED,
    SEARCHING,
    SCORING,
    DOWNLOADING,
    VALIDATING,
    STAGED,
    DELIVERING,
    PENDING_REVIEW,
    AUTO_FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED
}
