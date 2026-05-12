package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.impl.ProwlarrTorznabAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProwlarrTorznabAdapterTest {

    @Test
    void search_defaultMode_usesProwlarrNativeApiAndParsesReleases() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/search", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            byte[] body = """
                    [
                      {
                        "guid": "release-guid",
                        "title": "Example Book EPUB",
                        "size": 12345,
                        "publishDate": "2024-01-02T03:04:05Z",
                        "downloadUrl": "http://localhost/download/example.torrent",
                        "infoUrl": "http://localhost/info/example",
                        "protocol": "torrent"
                      }
                    ]
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Prowlarr")
                    .type(DownloadSourceType.PROWLARR_TORZNAB)
                    .credentialsJson("""
                            {
                              "baseUrl": "%s",
                              "apiKey": "secret",
                              "indexerIds": "1,2",
                              "categories": "7000",
                              "timeoutSeconds": 5
                            }
                            """.formatted(baseUrl))
                    .build();
            DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                    .query("Example")
                    .contentKind(DownloadContentKind.BOOK)
                    .maxResults(5)
                    .build();

            ProwlarrTorznabAdapter adapter = new ProwlarrTorznabAdapter(HttpClient.newHttpClient(), new ObjectMapper());
            List<NormalizedDownloadResult> results = adapter.search(source, criteria);

            assertEquals(1, results.size());
            assertTrue(requestPath.get().contains("/api/v1/search"));
            assertTrue(requestPath.get().contains("query=Example"));
            assertTrue(requestPath.get().contains("indexerIds=1"));
            assertTrue(requestPath.get().contains("indexerIds=2"));
            assertEquals("Example Book EPUB", results.getFirst().getTitle());
            assertEquals(DownloadAcquisitionType.TORRENT, results.getFirst().getAcquisitionType());
            assertEquals(12345L, results.getFirst().getSizeBytes());
            assertEquals(2024, results.getFirst().getPublishedYear());
        } finally {
            server.stop(0);
        }
    }
}
