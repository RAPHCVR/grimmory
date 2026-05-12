package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class DownloadSourceConfigReader {

    private final ObjectMapper objectMapper;

    public JsonNode credentials(DownloadSourceEntity source) {
        return read(source == null ? null : source.getCredentialsJson());
    }

    public JsonNode config(DownloadSourceEntity source) {
        return read(source == null ? null : source.getConfigJson());
    }

    public JsonNode firstSection(DownloadSourceEntity source, String section) {
        JsonNode configSection = config(source).path(section);
        if (!configSection.isMissingNode() && !configSection.isNull()) {
            return configSection;
        }
        JsonNode credentialSection = credentials(source).path(section);
        if (!credentialSection.isMissingNode() && !credentialSection.isNull()) {
            return credentialSection;
        }
        return objectMapper.createObjectNode();
    }

    public String firstText(DownloadSourceEntity source, String key, String defaultValue) {
        String fromConfig = config(source).path(key).asText(null);
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig;
        }
        String fromCredentials = credentials(source).path(key).asText(null);
        return fromCredentials == null || fromCredentials.isBlank() ? defaultValue : fromCredentials;
    }

    private JsonNode read(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }
}
