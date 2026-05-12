package org.booklore.service.downloads.dto;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class DownloadScoreBreakdown {
    int score;
    @Singular
    List<String> reasons;
}
