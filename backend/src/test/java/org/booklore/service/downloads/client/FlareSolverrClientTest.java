package org.booklore.service.downloads.client;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlareSolverrClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fetchPage_acceptsEndpointUrlWithV1AndExposesSolvedHeaders() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestedCommand = new AtomicReference<>();
        AtomicReference<Integer> requestedMaxTimeout = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            var body = objectMapper.readTree(exchange.getRequestBody());
            requestedCommand.set(body.path("cmd").asText());
            requestedMaxTimeout.set(body.path("maxTimeout").asInt());

            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of(
                            "response", "<html>ok</html>",
                            "userAgent", "Mozilla/5.0 Flare",
                            "cookies", List.of(
                                    Map.of("name", "cf_clearance", "value", "ok"),
                                    Map.of("name", "session", "value", "abc")
                            )
                    )
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String endpointUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Flare source")
                    .type(DownloadSourceType.DIRECT_URL)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "flareSolverr", Map.of(
                                    "url", endpointUrl,
                                    "maxTimeoutMs", 7000
                            )
                    )))
                    .build();

            FlareSolverrClient client = new FlareSolverrClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    new DownloadSourceConfigReader(objectMapper)
            );

            FlareSolverrClient.ResolvedPage page = client.fetchPage(source, "https://kagane.example/chapter");

            assertEquals("/v1", requestPath.get());
            assertEquals("request.get", requestedCommand.get());
            assertEquals(7000, requestedMaxTimeout.get());
            assertEquals("<html>ok</html>", page.response());
            assertEquals("Mozilla/5.0 Flare", page.headers().get("User-Agent"));
            assertEquals("cf_clearance=ok; session=abc", page.headers().get("Cookie"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveHeaders_sendsReturnOnlyCookiesWhenOnlyHeadersAreNeeded() throws Exception {
        AtomicInteger returnOnlyCookies = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            var body = objectMapper.readTree(exchange.getRequestBody());
            returnOnlyCookies.set(body.path("returnOnlyCookies").asBoolean(false) ? 1 : 0);
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of(
                            "userAgent", "Mozilla/5.0 CookieOnly",
                            "cookies", List.of(Map.of("name", "cf_clearance", "value", "solved"))
                    )
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Flare source")
                    .type(DownloadSourceType.DIRECT_URL)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "flareSolverr", Map.of("baseUrl", baseUrl)
                    )))
                    .build();
            FlareSolverrClient client = new FlareSolverrClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    new DownloadSourceConfigReader(objectMapper)
            );

            Map<String, String> headers = client.resolveHeaders(source, "https://kagane.example/chapter");

            assertEquals(1, returnOnlyCookies.get());
            assertEquals("Mozilla/5.0 CookieOnly", headers.get("User-Agent"));
            assertEquals("cf_clearance=solved", headers.get("Cookie"));
        } finally {
            server.stop(0);
        }
    }
}
