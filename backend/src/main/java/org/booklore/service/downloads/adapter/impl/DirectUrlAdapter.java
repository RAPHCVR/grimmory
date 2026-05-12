package org.booklore.service.downloads.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DirectUrlAdapter implements DownloadSourceAdapter {

    private final ObjectMapper objectMapper;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.DIRECT_URL;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        if (criteria.getDirectUrl() == null || criteria.getDirectUrl().isBlank()) {
            return List.of();
        }
        String fileName = extractFilename(criteria.getDirectUrl());
        DownloadFormat format = DownloadFormat.fromFileName(fileName).orElse(DownloadFormat.UNKNOWN);
        return List.of(NormalizedDownloadResult.builder()
                .sourceResultId(criteria.getDirectUrl())
                .title(criteria.getTitle() != null && !criteria.getTitle().isBlank() ? criteria.getTitle() : stripExtension(fileName))
                .authors(criteria.getAuthor() == null || criteria.getAuthor().isBlank() ? List.of() : List.of(criteria.getAuthor()))
                .seriesName(criteria.getSeriesName())
                .seriesNumber(criteria.getSeriesNumber())
                .isbn(criteria.getIsbn())
                .contentKind(criteria.getContentKind())
                .format(format)
                .downloadUrl(criteria.getDirectUrl())
                .detailsUrl(criteria.getDirectUrl())
                .requiresFlareSolverr(useFlareSolverr(source))
                .acquisitionType(inferAcquisitionType(criteria.getDirectUrl()))
                .build());
    }

    private String extractFilename(String value) {
        try {
            String path = URI.create(value).getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception e) {
            return "download";
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private DownloadAcquisitionType inferAcquisitionType(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        if (lower.startsWith("magnet:") || lower.endsWith(".torrent") || lower.contains(".torrent?")) {
            return DownloadAcquisitionType.TORRENT;
        }
        if (lower.endsWith(".nzb") || lower.contains(".nzb?")) {
            return DownloadAcquisitionType.NZB;
        }
        return DownloadAcquisitionType.DIRECT_FILE;
    }

    private boolean useFlareSolverr(DownloadSourceEntity source) {
        return boolFromJson(source.getConfigJson(), "useFlareSolverr")
                || boolFromJson(source.getCredentialsJson(), "useFlareSolverr")
                || nestedBoolFromJson(source.getConfigJson(), "flareSolverr", "enabled")
                || nestedBoolFromJson(source.getCredentialsJson(), "flareSolverr", "enabled");
    }

    private boolean boolFromJson(String json, String key) {
        try {
            if (json == null || json.isBlank()) return false;
            JsonNode root = objectMapper.readTree(json);
            return root.path(key).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean nestedBoolFromJson(String json, String parent, String key) {
        try {
            if (json == null || json.isBlank()) return false;
            JsonNode root = objectMapper.readTree(json);
            return root.path(parent).path(key).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
