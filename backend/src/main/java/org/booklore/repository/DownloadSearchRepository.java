package org.booklore.repository;

import org.booklore.model.entity.DownloadSearchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DownloadSearchRepository extends JpaRepository<DownloadSearchEntity, Long> {
}
