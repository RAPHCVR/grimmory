package org.booklore.service.downloads.adapter;

import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;

import java.util.List;

public interface DownloadSourceAdapter {

    DownloadSourceType sourceType();

    default boolean supports(DownloadSourceEntity source) {
        return source != null && sourceType() == source.getType();
    }

    List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria);
}
