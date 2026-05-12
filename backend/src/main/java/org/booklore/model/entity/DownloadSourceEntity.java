package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.DownloadSourceType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "download_source", uniqueConstraints = {
        @UniqueConstraint(name = "uq_download_source_name", columnNames = {"name"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 40, nullable = false)
    private DownloadSourceType type;

    @Lob
    @Column(name = "credentials_json", columnDefinition = "JSON")
    private String credentialsJson;

    @Lob
    @Column(name = "config_json", columnDefinition = "JSON")
    private String configJson;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
