package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.impl.AnnasArchiveApiAdapter;
import org.booklore.service.downloads.client.FlareSolverrClient;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnasArchiveApiAdapterTest {

    private static final String MD5 = "0123456789abcdef0123456789abcdef";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void search_fetchesRenderedHtmlViaFlareSolverrAndReturnsStacksMd5Results() throws Exception {
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        AtomicReference<String> requestedCommand = new AtomicReference<>();
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            var body = objectMapper.readTree(exchange.getRequestBody());
            requestedCommand.set(body.path("cmd").asText());
            requestedUrl.set(body.path("url").asText());

            String html = """
                    <html>
                      <body>
                        <a href="/md5/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" class="inline-block max-w-[50%] truncate">Irrelevant sidebar link</a>
                        <div>
                          <a href="/md5/0123456789abcdef0123456789abcdef" class="line-clamp-[3] js-vim-focus font-semibold">
                            Les Fourmis
                          </a>
                          <a href="/search?q=Werber%2C%20Bernard">
                            <span class="icon-[mdi--user-edit]"></span>
                            Werber, Bernard [Werber, Bernard]
                          </a>
                          <span>French EPUB 1.5 MB 1991</span>
                        </div>
                        <a href="/md5/0123456789abcdef0123456789abcdef">Duplicate</a>
                        <a href="/book/not-a-result">Ignored</a>
                      </body>
                    </html>
                    """;
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of(
                            "response", html,
                            "userAgent", "Mozilla/5.0",
                            "cookies", List.of()
                    )
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "baseUrl", "https://annas-archive.li",
                                    "searchPath", "/search",
                                    "defaultFormat", "epub",
                                    "maxResults", 10
                            ),
                            "flareSolverr", Map.of(
                                    "enabled", true,
                                    "baseUrl", flareSolverrBaseUrl,
                                    "maxTimeoutMs", 5000
                            )
                    )))
                    .build();
            AnnasArchiveApiAdapter adapter = adapter();

            var results = adapter.search(source, DownloadSearchCriteria.builder()
                    .query("Les Fourmis")
                    .author("Bernard Werber")
                    .contentKind(DownloadContentKind.BOOK)
                    .preferredFormats(List.of(DownloadFormat.EPUB))
                    .maxResults(10)
                    .build());

            assertEquals("request.get", requestedCommand.get());
            URI uri = URI.create(requestedUrl.get());
            assertEquals("https", uri.getScheme());
            assertEquals("annas-archive.li", uri.getHost());
            assertEquals("/search", uri.getPath());
            Map<String, String> queryParams = queryParams(uri.getRawQuery());
            assertEquals("Les Fourmis", queryParams.get("q"));
            assertNull(queryParams.get("ext"));

            assertEquals(1, results.size());
            var result = results.getFirst();
            assertEquals(MD5, result.getSourceResultId());
            assertTrue(result.getTitle().contains("Les Fourmis"));
            assertEquals(List.of("Bernard Werber"), result.getAuthors());
            assertEquals(DownloadFormat.EPUB, result.getFormat());
            assertEquals(DownloadAcquisitionType.EXTERNAL_STACKS, result.getAcquisitionType());
            assertEquals("https://annas-archive.li/md5/" + MD5, result.getDetailsUrl());
            assertNull(result.getDownloadUrl());
            assertTrue(result.isRequiresFlareSolverr());
            assertEquals(1991, result.getPublishedYear());
            assertEquals("fr", result.getLanguage());
            assertEquals(1_572_864L, result.getSizeBytes());
            assertTrue(result.getRawJson().contains(MD5));
        } finally {
            flareSolverr.stop(0);
        }
    }

    @Test
    void search_fallsBackToNextDomainWhenPrimaryRendersNoMd5Links() throws Exception {
        List<String> requestedUrls = new ArrayList<>();
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            var body = objectMapper.readTree(exchange.getRequestBody());
            String requestedUrl = body.path("url").asText();
            requestedUrls.add(requestedUrl);

            String html = requestedUrl.contains("annas-archive.gl")
                    ? "<html><a href=\"/md5/" + MD5 + "\">Les Fourmis Bernard Werber French EPUB 1.5 MB 1991</a></html>"
                    : "<html><title>Redirecting...</title><script>location.href='https://annas-archive.gl/search'</script></html>";
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of("response", html)
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "baseUrl", "https://annas-archive.li",
                                    "fallbackBaseUrls", List.of("https://annas-archive.gl"),
                                    "useDefaultFallbacks", false
                            ),
                            "flareSolverr", Map.of("baseUrl", flareSolverrBaseUrl)
                    )))
                    .build();

            var results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("Bernard Werber")
                    .contentKind(DownloadContentKind.BOOK)
                    .preferredFormats(List.of(DownloadFormat.EPUB))
                    .maxResults(10)
                    .build());

            assertEquals(2, requestedUrls.size());
            assertEquals("annas-archive.li", URI.create(requestedUrls.get(0)).getHost());
            assertEquals("annas-archive.gl", URI.create(requestedUrls.get(1)).getHost());
            assertEquals(1, results.size());
            assertEquals(MD5, results.getFirst().getSourceResultId());
            assertEquals("https://annas-archive.gl/md5/" + MD5, results.getFirst().getDetailsUrl());
        } finally {
            flareSolverr.stop(0);
        }
    }

    @Test
    void search_queriesAllPreferredFormatsAndParsesSequentialArtMetadata() throws Exception {
        List<String> requestedUrls = new ArrayList<>();
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            var body = objectMapper.readTree(exchange.getRequestBody());
            String requestedUrl = body.path("url").asText();
            requestedUrls.add(requestedUrl);

            String html = """
                    <html>
                      <body>
                        <div>
                          <a href="/md5/0123456789abcdef0123456789abcdef" class="js-vim-focus font-semibold">
                            Wakfu Manga - Tome 1: La Quête des Dofus Eliatropes
                          </a>
                          <a href="/search?q=Tot">
                            <span class="icon-[mdi--user-edit]"></span>
                            Tot
                          </a>
                          <span>zlib/Comics & Graphic Novels/Anime & Manga/Tot/Wakfu Manga - Tome 1: La Quête des Dofus Eliatropes_121004469.pdf French 2012, Wakfu, 1, 2012</span>
                        </div>
                      </body>
                    </html>
                    """;
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of("response", html)
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "baseUrl", "https://annas-archive.li",
                                    "useDefaultFallbacks", false
                            ),
                            "flareSolverr", Map.of("baseUrl", flareSolverrBaseUrl)
                    )))
                    .build();

            var results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("Wakfu Manga - Tome")
                    .contentKind(DownloadContentKind.AUTO)
                    .preferredFormats(List.of(DownloadFormat.EPUB, DownloadFormat.PDF))
                    .maxResults(10)
                    .build());

            assertEquals(1, requestedUrls.size());
            assertNull(queryParams(URI.create(requestedUrls.getFirst()).getRawQuery()).get("ext"));
            assertEquals(1, results.size());
            var result = results.getFirst();
            assertEquals(DownloadFormat.PDF, result.getFormat());
            assertEquals(DownloadContentKind.MANGA, result.getContentKind());
            assertEquals("Wakfu Manga", result.getSeriesName());
            assertEquals(1F, result.getSeriesNumber());
            assertEquals("La Quête des Dofus Eliatropes", result.getTitle());
            assertEquals(List.of("Tot"), result.getAuthors());
            assertEquals("fr", result.getLanguage());
        } finally {
            flareSolverr.stop(0);
        }
    }

    @Test
    void search_cleansPathBackedComicTitlesAndParsesDashNumberMetadata() throws Exception {
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String html = """
                    <html>
                      <body>
                        <div>
                          <a href="/md5/0123456789abcdef0123456789abcdef" class="js-vim-focus font-semibold">
                            lgli/I:\\comics3\\emule\\2020.05.24\\Manga Fr - Dragon Ball Super - 01 - Les Guerriers De L'univers - (Toriyama-Toyotarô) -.cbz
                            Manga Fr - Dragon Ball Super - 01 - Les Guerriers De L'univers - (Toriyama-Toyotarô) -.cbz
                          </a>
                          <span>French CBZ 2020</span>
                        </div>
                      </body>
                    </html>
                    """;
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of("response", html)
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "baseUrl", "https://annas-archive.li",
                                    "useDefaultFallbacks", false
                            ),
                            "flareSolverr", Map.of("baseUrl", flareSolverrBaseUrl)
                    )))
                    .build();

            var results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("Dragon Ball Super 24")
                    .contentKind(DownloadContentKind.MANGA)
                    .preferredFormats(List.of(DownloadFormat.CBZ))
                    .maxResults(10)
                    .build());

            assertEquals(1, results.size());
            var result = results.getFirst();
            assertEquals("Les Guerriers De L'univers - (Toriyama-Toyotarô) -", result.getTitle());
            assertEquals("Dragon Ball Super", result.getSeriesName());
            assertEquals(1F, result.getSeriesNumber());
            assertEquals(DownloadFormat.CBZ, result.getFormat());
            assertEquals(DownloadContentKind.MANGA, result.getContentKind());
        } finally {
            flareSolverr.stop(0);
        }
    }

    @Test
    void search_stripsTrailingPunctuationFromParsedSeriesNames() throws Exception {
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String html = """
                    <html>
                      <body>
                        <div>
                          <a href="/md5/0123456789abcdef0123456789abcdef" class="js-vim-focus font-semibold">
                            One Piece, Vol. 100
                          </a>
                          <a href="/search?q=Oda">
                            <span class="icon-[mdi--user-edit]"></span>
                            Oda, Eiichiro
                          </a>
                          <span>English EPUB 2010</span>
                        </div>
                      </body>
                    </html>
                    """;
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of("response", html)
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "baseUrl", "https://annas-archive.li",
                                    "useDefaultFallbacks", false
                            ),
                            "flareSolverr", Map.of("baseUrl", flareSolverrBaseUrl)
                    )))
                    .build();

            var results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("One Piece 100")
                    .contentKind(DownloadContentKind.MANGA)
                    .preferredFormats(List.of(DownloadFormat.EPUB))
                    .maxResults(10)
                    .build());

            assertEquals(1, results.size());
            var result = results.getFirst();
            assertEquals("One Piece", result.getSeriesName());
            assertEquals(100F, result.getSeriesNumber());
            assertEquals("One Piece, Vol. 100", result.getTitle());
        } finally {
            flareSolverr.stop(0);
        }
    }

    @Test
    void search_interleavesResultsAcrossPreferredFormatsBeforeApplyingLimit() throws Exception {
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            var body = objectMapper.readTree(exchange.getRequestBody());
            String requestedUrl = body.path("url").asText();

            String html = requestedUrl.contains("ext=pdf")
                    ? "<html><div><a href=\"/md5/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\" class=\"js-vim-focus font-semibold\">Les Fourmis PDF</a><span>Bernard Werber French PDF 1991</span></div></html>"
                    : """
                    <html>
                      <div><a href="/md5/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" class="js-vim-focus font-semibold">Les Fourmis EPUB</a><span>Bernard Werber French EPUB 1991</span></div>
                      <div><a href="/md5/cccccccccccccccccccccccccccccccc" class="js-vim-focus font-semibold">Les Thanatonautes EPUB</a><span>Bernard Werber French EPUB 1994</span></div>
                    </html>
                    """;
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of("response", html)
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "baseUrl", "https://annas-archive.li",
                                    "useDefaultFallbacks", false,
                                    "searchEachFormat", true
                            ),
                            "flareSolverr", Map.of("baseUrl", flareSolverrBaseUrl)
                    )))
                    .build();

            var results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("Bernard Werber")
                    .contentKind(DownloadContentKind.BOOK)
                    .preferredFormats(List.of(DownloadFormat.EPUB, DownloadFormat.PDF))
                    .maxResults(3)
                    .build());

            assertEquals(3, results.size());
            assertEquals(DownloadFormat.EPUB, results.get(0).getFormat());
            assertEquals(DownloadFormat.PDF, results.get(1).getFormat());
            assertEquals(DownloadFormat.EPUB, results.get(2).getFormat());
        } finally {
            flareSolverr.stop(0);
        }
    }

    @Test
    void search_usesSeriesVolumeTitleWhenStacksResultTitleIsOnlyEditionSuffix() throws Exception {
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String html = """
                    <html>
                      <body>
                        <div>
                          <a href="/md5/0123456789abcdef0123456789abcdef" class="line-clamp-[3] js-vim-focus font-semibold">
                            (Big Kana) (French Edition)
                          </a>
                          <a href="/search?q=Asano%2C%20Inio">
                            <span class="icon-[mdi--user-edit]"></span>
                            Asano, Inio
                          </a>
                          <span>French CBZ 109 MB, Bonne Nuit Punpun, 2, 2012</span>
                        </div>
                      </body>
                    </html>
                    """;
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of("response", html)
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of(
                                    "baseUrl", "https://annas-archive.li",
                                    "searchPath", "/search",
                                    "defaultFormat", "cbz",
                                    "maxResults", 10
                            ),
                            "flareSolverr", Map.of("baseUrl", flareSolverrBaseUrl)
                    )))
                    .build();

            var results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("bonne nuit punpun")
                    .contentKind(DownloadContentKind.MANGA)
                    .preferredFormats(List.of(DownloadFormat.CBZ))
                    .maxResults(10)
                    .build());

            assertEquals(1, results.size());
            var result = results.getFirst();
            assertEquals("Bonne Nuit Punpun VOLUME 2", result.getTitle());
            assertEquals("Bonne Nuit Punpun", result.getSeriesName());
            assertEquals(2F, result.getSeriesNumber());
            assertEquals(DownloadContentKind.MANGA, result.getContentKind());
            assertEquals(DownloadFormat.CBZ, result.getFormat());
        } finally {
            flareSolverr.stop(0);
        }
    }

    @Test
    void search_whenHtmlContainsNoMd5Links_returnsNoResults() throws Exception {
        HttpServer flareSolverr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flareSolverr.createContext("/v1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of("response", "<html><a href=\"/search?q=test\">No result</a></html>")
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        flareSolverr.start();

        try {
            String flareSolverrBaseUrl = "http://127.0.0.1:" + flareSolverr.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Anna HTML")
                    .type(DownloadSourceType.ANNAS_ARCHIVE_API)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "annasArchiveApi", Map.of("baseUrl", "https://annas-archive.li/search"),
                            "flareSolverr", Map.of("baseUrl", flareSolverrBaseUrl)
                    )))
                    .build();

            var results = adapter().search(source, DownloadSearchCriteria.builder()
                    .query("Bernard Werber")
                    .contentKind(DownloadContentKind.BOOK)
                    .maxResults(10)
                    .build());

            assertTrue(results.isEmpty());
        } finally {
            flareSolverr.stop(0);
        }
    }

    private AnnasArchiveApiAdapter adapter() {
        DownloadSourceConfigReader configReader = new DownloadSourceConfigReader(objectMapper);
        return new AnnasArchiveApiAdapter(
                new FlareSolverrClient(HttpClient.newHttpClient(), objectMapper, configReader),
                objectMapper,
                configReader,
                new DownloadContentClassifier()
        );
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
