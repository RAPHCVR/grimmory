package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "download_result", indexes = {
        @Index(name = "idx_download_result_search_score", columnList = "search_id, score"),
        @Index(name = "idx_download_result_source", columnList = "source_id"),
        @Index(name = "idx_download_result_external", columnList = "external_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "search_id", nullable = false)
    private DownloadSearchEntity search;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private DownloadSourceEntity source;

    @Column(name = "external_id", length = 512)
    private String externalId;

    @Column(name = "title", length = 512, nullable = false)
    private String title;

    @Lob
    @Column(name = "authors_json", columnDefinition = "JSON")
    private String authorsJson;

    @Column(name = "series_name", length = 512)
    private String seriesName;

    @Column(name = "series_number")
    private Float seriesNumber;

    @Column(name = "published_year")
    private Integer publishedYear;

    @Column(name = "isbn", length = 32)
    private String isbn;

    @Column(name = "language", length = 32)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", length = 20, nullable = false)
    @Builder.Default
    private DownloadFormat format = DownloadFormat.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_kind", length = 20, nullable = false)
    private DownloadContentKind contentKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_type", length = 30, nullable = false)
    @Builder.Default
    private DownloadAcquisitionType acquisitionType = DownloadAcquisitionType.UNKNOWN;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "download_url", columnDefinition = "TEXT")
    private String downloadUrl;

    @Column(name = "details_url", columnDefinition = "TEXT")
    private String detailsUrl;

    @Column(name = "requires_flare_solverr", nullable = false)
    @Builder.Default
    private Boolean requiresFlareSolverr = Boolean.FALSE;

    @Column(name = "score")
    private Integer score;

    @Column(name = "score_reasons", columnDefinition = "TEXT")
    private String scoreReasons;

    @Lob
    @Column(name = "raw_json", columnDefinition = "JSON")
    private String rawJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
