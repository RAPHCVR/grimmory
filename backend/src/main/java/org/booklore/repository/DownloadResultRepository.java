package org.booklore.repository;

import org.booklore.model.entity.DownloadResultEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DownloadResultRepository extends JpaRepository<DownloadResultEntity, Long> {
    @EntityGraph(attributePaths = {"search", "source"})
    List<DownloadResultEntity> findAllBySearchIdOrderByScoreDescIdAsc(Long searchId);

    @EntityGraph(attributePaths = {"search", "source"})
    Optional<DownloadResultEntity> findWithSearchAndSourceById(Long id);
}
