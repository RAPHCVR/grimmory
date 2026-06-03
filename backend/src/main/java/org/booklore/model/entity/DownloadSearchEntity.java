package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadSearchStatus;
import org.booklore.model.enums.DownloadSequenceNumberType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "download_search", indexes = {
        @Index(name = "idx_download_search_content_kind", columnList = "content_kind"),
        @Index(name = "idx_download_search_canonical_provider", columnList = "canonical_provider"),
        @Index(name = "idx_download_search_created_at", columnList = "created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadSearchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "query", length = 512, nullable = false)
    private String query;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "author", length = 512)
    private String author;

    @Column(name = "isbn", length = 32)
    private String isbn;

    @Column(name = "series_name", length = 512)
    private String seriesName;

    @Column(name = "series_number")
    private Float seriesNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_kind", length = 20, nullable = false)
    private DownloadContentKind contentKind;

    @Lob
    @Column(name = "preferred_formats_json", columnDefinition = "JSON")
    private String preferredFormatsJson;

    @Column(name = "canonical_provider", length = 64)
    private String canonicalProvider;

    @Enumerated(EnumType.STRING)
    @Column(name = "canonical_content_kind", length = 20)
    private DownloadContentKind canonicalContentKind;

    @Column(name = "canonical_title", length = 512)
    private String canonicalTitle;

    @Column(name = "canonical_author", length = 512)
    private String canonicalAuthor;

    @Column(name = "canonical_isbn", length = 32)
    private String canonicalIsbn;

    @Column(name = "canonical_series_name", length = 512)
    private String canonicalSeriesName;

    @Column(name = "canonical_series_number")
    private Float canonicalSeriesNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "canonical_sequence_number_type", length = 20)
    private DownloadSequenceNumberType canonicalSequenceNumberType;

    @Column(name = "canonical_confidence")
    private Double canonicalConfidence;

    @Column(name = "canonical_cover_url", columnDefinition = "TEXT")
    private String canonicalCoverUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private DownloadSearchStatus status = DownloadSearchStatus.CREATED;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @OneToMany(mappedBy = "search", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DownloadResultEntity> results = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
