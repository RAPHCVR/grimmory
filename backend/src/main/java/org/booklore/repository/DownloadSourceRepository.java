package org.booklore.repository;

import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DownloadSourceRepository extends JpaRepository<DownloadSourceEntity, Long> {
    List<DownloadSourceEntity> findAllByEnabledTrueOrderByPriorityAscNameAsc();
    List<DownloadSourceEntity> findAllByEnabledTrueAndTypeOrderByPriorityAscNameAsc(DownloadSourceType type);
}
