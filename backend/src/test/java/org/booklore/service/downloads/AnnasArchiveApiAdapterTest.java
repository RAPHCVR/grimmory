package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.impl.AnnasArchiveApiAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnasArchiveApiAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void search_callsConfiguredJsonApiAndNormalizesResults() throws Exception {
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<Map<String, String>> queryParams = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            queryParams.set(queryParams(exchange.getRequestURI().getRawQuery()));
            byte[] body = """
                    {
                      "results": [
                        {
                          "md5": "abc123",
                          "title": "Les Fourmis",
                          "authors": ["Bernard Werber"],
                          "language": "fr",
                          "extension": "epub",
                          "downloadUrl": "https://bridge.example/download/abc123.epub",
                          "detailsUrl": "https://bridge.example/book/abc123",
                          "size": "1.5 MB",
                          "year": "1991",
                          "isbn": "9782253063336"
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna bridge")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .credentialsJson(objectMapper.writeValueAsString(Map.of(
                            "baseUrl", baseUrl,
                            "apiKey", "secret"
                    )))
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "resultsPath", "results",
                                    "timeoutSeconds", 5,
                                    "requiresFlareSolverr", true
                            )
                    )))
                    .build();
            AnnasArchiveApiAdapter adapter = new AnnasArchiveApiAdapter(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    new DownloadSourceConfigReader(objectMapper)
            );

            var results = adapter.search(source, DownloadSearchCriteria.builder()
                    .query("Bernard Werber")
                    .contentKind(DownloadContentKind.BOOK)
                    .preferredFormats(List.of(DownloadFormat.EPUB))
                    .maxResults(10)
                    .build());

            assertEquals("secret", apiKeyHeader.get());
            assertEquals("Bernard Werber", queryParams.get().get("q"));
            assertEquals("epub", queryParams.get().get("ext"));
            assertEquals("10", queryParams.get().get("limit"));
            assertEquals(1, results.size());
            var result = results.getFirst();
            assertEquals("abc123", result.getSourceResultId());
            assertEquals("Les Fourmis", result.getTitle());
            assertEquals(List.of("Bernard Werber"), result.getAuthors());
            assertEquals(DownloadFormat.EPUB, result.getFormat());
            assertEquals(DownloadAcquisitionType.DIRECT_FILE, result.getAcquisitionType());
            assertTrue(result.isRequiresFlareSolverr());
            assertEquals(1_572_864L, result.getSizeBytes());
            assertEquals("9782253063336", result.getIsbn());
        } finally {
            server.stop(0);
        }
    }

    private Map<String, String> queryParams(String rawQuery) {
        return rawQuery == null || rawQuery.isBlank()
                ? Map.of()
                : java.util.Arrays.stream(rawQuery.split("&"))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        part -> decode(part[0]),
                        part -> part.length > 1 ? decode(part[1]) : ""
                ));
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
