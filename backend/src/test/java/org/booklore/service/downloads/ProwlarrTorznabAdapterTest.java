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
                        "infoHash": "0123456789abcdef0123456789abcdef01234567",
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

            ProwlarrTorznabAdapter adapter = adapter();
            List<NormalizedDownloadResult> results = adapter.search(source, criteria);

            assertEquals(1, results.size());
            assertTrue(requestPath.get().contains("/api/v1/search"));
            assertTrue(requestPath.get().contains("query=Example"));
            assertTrue(requestPath.get().contains("indexerIds=1"));
            assertTrue(requestPath.get().contains("indexerIds=2"));
            assertEquals("Example Book EPUB", results.getFirst().getTitle());
            assertEquals("0123456789abcdef0123456789abcdef01234567", results.getFirst().getSourceResultId());
            assertEquals(DownloadAcquisitionType.TORRENT, results.getFirst().getAcquisitionType());
            assertEquals(12345L, results.getFirst().getSizeBytes());
            assertEquals(2024, results.getFirst().getPublishedYear());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void search_autoMode_infersMangaFromNyaaVolumeRelease() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/search", exchange -> {
            byte[] body = """
                    [
                      {
                        "guid": "one-piece-100",
                        "infoHash": "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
                        "title": "[ENG] One Piece - Vol. 100 (FULL COLOR Digital Colored Comics)",
                        "indexer": "Nyaa",
                        "size": 159593264,
                        "downloadUrl": "magnet:?xt=urn:btih:abcdefabcdefabcdefabcdefabcdefabcdefabcd",
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
                    .name("Prowlarr Local")
                    .type(DownloadSourceType.PROWLARR_TORZNAB)
                    .credentialsJson("""
                            {
                              "baseUrl": "%s",
                              "apiKey": "secret",
                              "timeoutSeconds": 5
                            }
                            """.formatted(baseUrl))
                    .build();

            List<NormalizedDownloadResult> results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("One Piece 100")
                    .contentKind(DownloadContentKind.AUTO)
                    .maxResults(5)
                    .build());

            assertEquals(1, results.size());
            assertEquals(DownloadContentKind.MANGA, results.getFirst().getContentKind());
            assertEquals(DownloadAcquisitionType.TORRENT, results.getFirst().getAcquisitionType());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void search_skipsProwlarrVideoPayloadsBeforeScoring() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/search", exchange -> {
            byte[] body = """
                    [
                      {
                        "guid": "anime-video",
                        "infoHash": "1111111111111111111111111111111111111111",
                        "title": "DBF - Dragon Ball Super #24 FULLHD - Sub-Ita -",
                        "indexer": "Nyaa",
                        "categories": [{"name": "Anime"}],
                        "size": 561000000,
                        "downloadUrl": "magnet:?xt=urn:btih:1111111111111111111111111111111111111111",
                        "protocol": "torrent"
                      },
                      {
                        "guid": "game-repack",
                        "infoHash": "3333333333333333333333333333333333333333",
                        "title": "ONE PIECE ODYSSEY: Deluxe Edition (+ 6 DLCs, MULTi15) [FitGirl Repack]",
                        "indexer": "1337x",
                        "categories": [{"name": "Games/PC"}],
                        "size": 30000000000,
                        "downloadUrl": "magnet:?xt=urn:btih:3333333333333333333333333333333333333333",
                        "protocol": "torrent"
                      },
                      {
                        "guid": "adult-video-noise",
                        "infoHash": "4444444444444444444444444444444444444444",
                        "title": "HD GS 323 cleaning staff began my time one piece pants girl into the adult toys in Masturbation",
                        "indexer": "1337x",
                        "categories": [{"name": "XXX"}],
                        "size": 570000000,
                        "downloadUrl": "magnet:?xt=urn:btih:4444444444444444444444444444444444444444",
                        "protocol": "torrent"
                      },
                      {
                        "guid": "manga-release",
                        "infoHash": "2222222222222222222222222222222222222222",
                        "title": "Dragon Ball Super - Vol.24 - Full Color (Ch101 - Ch104)",
                        "indexer": "Nyaa",
                        "categories": [{"name": "Literature"}],
                        "size": 158000000,
                        "downloadUrl": "magnet:?xt=urn:btih:2222222222222222222222222222222222222222",
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
                    .name("Prowlarr Local")
                    .type(DownloadSourceType.PROWLARR_TORZNAB)
                    .credentialsJson("""
                            {
                              "baseUrl": "%s",
                              "apiKey": "secret",
                              "timeoutSeconds": 5
                            }
                            """.formatted(baseUrl))
                    .build();

            List<NormalizedDownloadResult> results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("Dragon Ball Super 24")
                    .contentKind(DownloadContentKind.MANGA)
                    .maxResults(5)
                    .build());

            assertEquals(1, results.size());
            assertEquals("Dragon Ball Super - Vol.24 - Full Color (Ch101 - Ch104)", results.getFirst().getTitle());
            assertEquals(DownloadContentKind.MANGA, results.getFirst().getContentKind());
        } finally {
            server.stop(0);
        }
    }

    private ProwlarrTorznabAdapter adapter() {
        return new ProwlarrTorznabAdapter(HttpClient.newHttpClient(), new ObjectMapper(), new DownloadContentClassifier());
    }
}
