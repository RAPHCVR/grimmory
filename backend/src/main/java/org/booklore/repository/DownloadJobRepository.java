package org.booklore.repository;

import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadJobStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DownloadJobRepository extends JpaRepository<DownloadJobEntity, Long> {
    List<DownloadJobEntity> findAllByStatusOrderByCreatedAtAsc(DownloadJobStatus status);

    List<DownloadJobEntity> findAllByStatusIn(Collection<DownloadJobStatus> statuses);

    List<DownloadJobEntity> findAllByStatusInAndLastProgressAtBefore(Collection<DownloadJobStatus> statuses, Instant cutoff);

    List<DownloadJobEntity> findAllByStatusAndDeliveredFilePath(DownloadJobStatus status, String deliveredFilePath);

    @EntityGraph(attributePaths = {"search", "result", "source"})
    Optional<DownloadJobEntity> findWithSearchAndResultAndSourceById(Long id);

    @Query("""
            SELECT job
            FROM DownloadJobEntity job
            JOIN FETCH job.search
            JOIN FETCH job.result result
            JOIN FETCH job.source source
            WHERE source.id = :sourceId
              AND job.status IN :statuses
              AND (
                    (:externalId IS NOT NULL AND result.externalId = :externalId)
                 OR (:detailsUrl IS NOT NULL AND result.detailsUrl = :detailsUrl)
                 OR (:downloadUrl IS NOT NULL AND result.downloadUrl = :downloadUrl)
              )
            ORDER BY job.createdAt DESC, job.id DESC
            """)
    List<DownloadJobEntity> findReusableByResultFingerprint(Long sourceId,
                                                            String externalId,
                                                            String detailsUrl,
                                                            String downloadUrl,
                                                            Collection<DownloadJobStatus> statuses);
}
