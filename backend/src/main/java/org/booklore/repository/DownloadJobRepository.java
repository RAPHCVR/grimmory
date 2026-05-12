package org.booklore.repository;

import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.enums.DownloadJobStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @EntityGraph(attributePaths = {"search", "result", "source"})
    Optional<DownloadJobEntity> findWithSearchAndResultAndSourceById(Long id);
}
