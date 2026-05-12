package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.impl.MangaDexAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MangaDexAdapterTest {

    @Test
    void search_withoutConfiguredLanguage_doesNotFilterAndReturnsAvailableChapters() throws Exception {
        AtomicReference<String> mangaPath = new AtomicReference<>();
        AtomicReference<String> feedPath = new AtomicReference<>();
        HttpServer server = mangaDexServer(mangaPath, feedPath);
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DownloadSourceEntity source = source("""
                    {
                      "mangadex": {
                        "apiBaseUrl": "%s",
                        "siteBaseUrl": "https://mangadex.local",
                        "timeoutSeconds": 5
                      }
                    }
                    """.formatted(baseUrl));

            List<NormalizedDownloadResult> results = adapter().search(source, criteria());

            assertEquals(1, results.size());
            assertFalse(decoded(mangaPath).contains("availableTranslatedLanguage[]"));
            assertFalse(decoded(feedPath).contains("translatedLanguage[]"));
            NormalizedDownloadResult result = results.getFirst();
            assertEquals("Capitolo 1", result.getTitle());
            assertEquals("Wakfu", result.getSeriesName());
            assertEquals(1.0F, result.getSeriesNumber());
            assertEquals("it", result.getLanguage());
            assertEquals(DownloadFormat.CBZ, result.getFormat());
            assertEquals("https://mangadex.local/chapter/chapter-id", result.getDetailsUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void search_withConfiguredLanguages_appliesLanguageFilters() throws Exception {
        AtomicReference<String> mangaPath = new AtomicReference<>();
        AtomicReference<String> feedPath = new AtomicReference<>();
        HttpServer server = mangaDexServer(mangaPath, feedPath);
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DownloadSourceEntity source = source("""
                    {
                      "mangadex": {
                        "apiBaseUrl": "%s",
                        "siteBaseUrl": "https://mangadex.local",
                        "translatedLanguages": ["fr", "en"],
                        "timeoutSeconds": 5
                      }
                    }
                    """.formatted(baseUrl));

            List<NormalizedDownloadResult> results = adapter().search(source, criteria());

            assertEquals(1, results.size());
            assertTrue(decoded(mangaPath).contains("availableTranslatedLanguage[]=fr"));
            assertTrue(decoded(mangaPath).contains("availableTranslatedLanguage[]=en"));
            assertTrue(decoded(feedPath).contains("translatedLanguage[]=fr"));
            assertTrue(decoded(feedPath).contains("translatedLanguage[]=en"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void search_autoContentKind_includesMangaDexResultsAsManga() throws Exception {
        AtomicReference<String> mangaPath = new AtomicReference<>();
        AtomicReference<String> feedPath = new AtomicReference<>();
        HttpServer server = mangaDexServer(mangaPath, feedPath);
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DownloadSourceEntity source = source("""
                    {
                      "mangadex": {
                        "apiBaseUrl": "%s",
                        "siteBaseUrl": "https://mangadex.local",
                        "timeoutSeconds": 5
                      }
                    }
                    """.formatted(baseUrl));

            List<NormalizedDownloadResult> results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("Wakfu")
                    .contentKind(DownloadContentKind.AUTO)
                    .maxResults(10)
                    .build());

            assertEquals(1, results.size());
            assertEquals(DownloadContentKind.MANGA, results.getFirst().getContentKind());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer mangaDexServer(AtomicReference<String> mangaPath,
                                      AtomicReference<String> feedPath) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/manga/manga-id/feed", exchange -> {
            feedPath.set(exchange.getRequestURI().toString());
            byte[] body = """
                    {
                      "data": [
                        {
                          "id": "chapter-id",
                          "attributes": {
                            "title": "Capitolo 1",
                            "chapter": "1",
                            "volume": "1",
                            "translatedLanguage": "it"
                          }
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/manga", exchange -> {
            mangaPath.set(exchange.getRequestURI().toString());
            byte[] body = """
                    {
                      "data": [
                        {
                          "id": "manga-id",
                          "attributes": {
                            "title": {"it": "Wakfu"},
                            "altTitles": [{"fr": "Wakfu - La Grande Vague"}],
                            "availableTranslatedLanguages": ["it"]
                          },
                          "relationships": [
                            {"type": "author", "attributes": {"name": "Ankama"}}
                          ]
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private MangaDexAdapter adapter() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new MangaDexAdapter(HttpClient.newHttpClient(), objectMapper, new DownloadSourceConfigReader(objectMapper));
    }

    private DownloadSourceEntity source(String credentialsJson) {
        return DownloadSourceEntity.builder()
                .name("MangaDex")
                .type(DownloadSourceType.MANGADEX)
                .credentialsJson(credentialsJson)
                .build();
    }

    private DownloadSearchCriteria criteria() {
        return DownloadSearchCriteria.builder()
                .query("Wakfu")
                .contentKind(DownloadContentKind.MANGA)
                .maxResults(10)
                .build();
    }

    private String decoded(AtomicReference<String> value) {
        return URLDecoder.decode(value.get(), StandardCharsets.UTF_8);
    }
}
