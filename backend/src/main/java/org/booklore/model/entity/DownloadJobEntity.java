package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.DownloadJobStatus;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "download_job", indexes = {
        @Index(name = "idx_download_job_status", columnList = "status"),
        @Index(name = "idx_download_job_created_at", columnList = "created_at"),
        @Index(name = "idx_download_job_result", columnList = "result_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "search_id", nullable = false)
    private DownloadSearchEntity search;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "result_id", nullable = false)
    private DownloadResultEntity result;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private DownloadSourceEntity source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    @Builder.Default
    private DownloadJobStatus status = DownloadJobStatus.QUEUED;

    @Column(name = "progress_percent")
    @Builder.Default
    private Integer progressPercent = 0;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    @Column(name = "auto_finalize", nullable = false)
    @Builder.Default
    private Boolean autoFinalize = Boolean.FALSE;

    @Column(name = "confidence_threshold", nullable = false)
    @Builder.Default
    private Integer confidenceThreshold = 90;

    @Column(name = "fallback_enabled", nullable = false)
    @Builder.Default
    private Boolean fallbackEnabled = Boolean.FALSE;

    @Column(name = "target_library_id")
    private Long targetLibraryId;

    @Column(name = "target_library_path_id")
    private Long targetLibraryPathId;

    @Column(name = "staging_dir", columnDefinition = "TEXT")
    private String stagingDir;

    @Column(name = "part_file_path", columnDefinition = "TEXT")
    private String partFilePath;

    @Column(name = "external_task_id")
    private String externalTaskId;

    @Column(name = "external_task_type", length = 64)
    private String externalTaskType;

    @Column(name = "staged_file_path", columnDefinition = "TEXT")
    private String stagedFilePath;

    @Column(name = "delivered_file_path", columnDefinition = "TEXT")
    private String deliveredFilePath;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
