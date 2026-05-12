package org.booklore.service.downloads.client;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FlareSolverrClient {

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;

    public Map<String, String> resolveHeaders(DownloadSourceEntity source, String url) {
        FlareSolverrConfig config = readConfig(source);
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("cmd", "request.get");
            body.put("url", url);
            body.put("maxTimeout", config.maxTimeoutMs());
            body.put("returnOnlyCookies", true);

            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/v1"))
                    .timeout(Duration.ofMillis(config.maxTimeoutMs() + 5_000L))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("FlareSolverr failed with HTTP status " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!"ok".equalsIgnoreCase(root.path("status").asText())) {
                throw new DownloadSourceException("FlareSolverr failed: " + root.path("message").asText("unknown error"));
            }
            JsonNode solution = root.path("solution");
            Map<String, String> headers = new LinkedHashMap<>();
            String userAgent = solution.path("userAgent").asText(null);
            if (userAgent != null && !userAgent.isBlank()) {
                headers.put("User-Agent", userAgent);
            }
            String cookies = cookieHeader(solution.path("cookies"));
            if (!cookies.isBlank()) {
                headers.put("Cookie", cookies);
            }
            return headers;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("FlareSolverr request interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("FlareSolverr request failed: " + e.getMessage(), e);
        }
    }

    private String cookieHeader(JsonNode cookies) {
        if (cookies == null || !cookies.isArray()) {
            return "";
        }
        StringBuilder header = new StringBuilder();
        for (JsonNode cookie : cookies) {
            String name = cookie.path("name").asText(null);
            String value = cookie.path("value").asText(null);
            if (name == null || name.isBlank() || value == null) {
                continue;
            }
            if (!header.isEmpty()) {
                header.append("; ");
            }
            header.append(name).append('=').append(value);
        }
        return header.toString();
    }

    private FlareSolverrConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "flareSolverr");
        String baseUrl = node.path("baseUrl").asText(null);
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = configReader.firstText(source, "flareSolverrBaseUrl", null);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new DownloadSourceException("Source requires flareSolverr.baseUrl when requiresFlareSolverr=true");
        }
        int maxTimeoutMs = Math.max(5_000, node.path("maxTimeoutMs").asInt(DEFAULT_TIMEOUT_MS));
        return new FlareSolverrConfig(trimTrailingSlash(baseUrl), maxTimeoutMs);
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record FlareSolverrConfig(String baseUrl, int maxTimeoutMs) {
    }
}
