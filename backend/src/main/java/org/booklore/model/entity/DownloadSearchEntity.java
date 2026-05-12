package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadSearchStatus;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "download_search", indexes = {
        @Index(name = "idx_download_search_content_kind", columnList = "content_kind"),
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
