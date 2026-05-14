package org.booklore.service.downloads.client;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FlareSolverrClient {

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;

    @Value("${grimmory.downloads.flaresolverr.url:http://localhost:8191/v1}")
    String defaultEndpointUrl = "http://localhost:8191/v1";

    @Value("${grimmory.downloads.flaresolverr.timeout-ms:60000}")
    int defaultTimeoutMs = DEFAULT_TIMEOUT_MS;

    public String fetchHtml(DownloadSourceEntity source, String url) {
        String html = fetchPage(source, url).response();
        if (html == null || html.isBlank()) {
            throw new DownloadSourceException("FlareSolverr response did not contain rendered HTML");
        }
        return html;
    }

    public ResolvedPage fetchPage(DownloadSourceEntity source, String url) {
        return requestGet(source, url, false);
    }

    public Map<String, String> resolveHeaders(DownloadSourceEntity source, String url) {
        return requestGet(source, url, true).headers();
    }

    private ResolvedPage requestGet(DownloadSourceEntity source, String url, boolean returnOnlyCookies) {
        FlareSolverrConfig config = readConfig(source);
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("cmd", "request.get");
            body.put("url", url);
            body.put("maxTimeout", config.maxTimeoutMs());
            if (returnOnlyCookies) {
                body.put("returnOnlyCookies", true);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpointUrl()))
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
            if (solution.isMissingNode() || solution.isNull()) {
                throw new DownloadSourceException("FlareSolverr response did not contain a solution");
            }
            return parseSolution(solution);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("FlareSolverr request interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("FlareSolverr request failed: " + e.getMessage(), e);
        }
    }

    private ResolvedPage parseSolution(JsonNode solution) {
        String response = solution.path("response").asText(null);
        String userAgent = solution.path("userAgent").asText(null);
        return new ResolvedPage(response, parseCookies(solution.path("cookies")), blankToNull(userAgent));
    }

    private List<SolvedCookie> parseCookies(JsonNode cookies) {
        if (cookies == null || !cookies.isArray()) {
            return List.of();
        }
        List<SolvedCookie> parsed = new ArrayList<>();
        for (JsonNode cookie : cookies) {
            String name = cookie.path("name").asText(null);
            String value = cookie.path("value").asText(null);
            if (name == null || name.isBlank() || value == null) {
                continue;
            }
            parsed.add(new SolvedCookie(name.trim(), value));
        }
        return List.copyOf(parsed);
    }

    private FlareSolverrConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "flareSolverr");
        String endpointUrl = firstNonBlank(
                node.path("url").asText(null),
                node.path("endpointUrl").asText(null),
                node.path("baseUrl").asText(null),
                configReader.firstText(source, "flareSolverrUrl", null),
                configReader.firstText(source, "flareSolverrBaseUrl", null),
                defaultEndpointUrl
        );
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new DownloadSourceException("FlareSolverr endpoint URL is not configured");
        }
        int maxTimeoutMs = Math.max(5_000, node.path("maxTimeoutMs").asInt(node.path("timeoutMs").asInt(defaultTimeoutMs)));
        return new FlareSolverrConfig(normalizeEndpointUrl(endpointUrl), maxTimeoutMs);
    }

    private String normalizeEndpointUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ResolvedPage(String response, List<SolvedCookie> cookies, String userAgent) {

        public Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            if (userAgent != null && !userAgent.isBlank()) {
                headers.put("User-Agent", userAgent);
            }
            String cookieHeader = cookieHeader();
            if (!cookieHeader.isBlank()) {
                headers.put("Cookie", cookieHeader);
            }
            return headers;
        }

        public String cookieHeader() {
            if (cookies == null || cookies.isEmpty()) {
                return "";
            }
            StringBuilder header = new StringBuilder();
            for (SolvedCookie cookie : cookies) {
                if (cookie == null || cookie.name() == null || cookie.name().isBlank() || cookie.value() == null) {
                    continue;
                }
                if (!header.isEmpty()) {
                    header.append("; ");
                }
                header.append(cookie.name()).append('=').append(cookie.value());
            }
            return header.toString();
        }
    }

    public record SolvedCookie(String name, String value) {
    }

    private record FlareSolverrConfig(String endpointUrl, int maxTimeoutMs) {
    }
}
