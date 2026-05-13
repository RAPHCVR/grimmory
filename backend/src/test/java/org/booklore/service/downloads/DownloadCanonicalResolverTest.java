package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadCanonicalResolverTest {

    private final DownloadQueryIntentParser parser = new DownloadQueryIntentParser();

    @Test
    void resolvesBookAgainstOpenLibrary() throws Exception {
        HttpServer server = jsonServer("/search.json", """
                {
                  "docs": [
                    {
                      "title": "Pride and Prejudice",
                      "author_name": ["Jane Austen"],
                      "isbn": ["9780141439518", "0141439513"]
                    }
                  ]
                }
                """);
        server.start();
        try {
            DownloadCanonicalResolver resolver = resolver();
            resolver.openLibraryBaseUrl = baseUrl(server);
            resolver.googleBooksEnabled = false;
            resolver.mangaDexEnabled = false;
            resolver.webtoonsEnabled = false;

            DownloadSearchCriteria resolved = resolver.resolve(DownloadSearchCriteria.builder()
                    .query("pride prejudice jane austen")
                    .contentKind(DownloadContentKind.BOOK)
                    .build());

            assertThat(resolved.getTitle()).isEqualTo("Pride and Prejudice");
            assertThat(resolved.getAuthor()).isEqualTo("Jane Austen");
            assertThat(resolved.getIsbn()).isEqualTo("9780141439518");
            assertThat(resolved.getQuery()).isEqualTo("Pride and Prejudice Jane Austen");
            assertThat(resolved.getContentKind()).isEqualTo(DownloadContentKind.BOOK);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesMangaAgainstMangaDexWithoutDroppingRequestedChapter() throws Exception {
        HttpServer server = jsonServer("/manga", """
                {
                  "data": [
                    {
                      "id": "manga-1",
                      "attributes": {
                        "title": {"en": "Dragon Ball Super"}
                      },
                      "relationships": [
                        {"type": "author", "attributes": {"name": "Akira Toriyama"}}
                      ]
                    }
                  ]
                }
                """);
        server.start();
        try {
            DownloadCanonicalResolver resolver = resolver();
            resolver.openLibraryEnabled = false;
            resolver.googleBooksEnabled = false;
            resolver.mangaDexBaseUrl = baseUrl(server);
            resolver.webtoonsEnabled = false;

            DownloadSearchCriteria parsed = parser.enrich(DownloadSearchCriteria.builder()
                    .query("Dragon Ball Super vol 24")
                    .contentKind(DownloadContentKind.MANGA)
                    .build());

            DownloadSearchCriteria resolved = resolver.resolve(parsed);

            assertThat(resolved.getTitle()).isEqualTo("Dragon Ball Super");
            assertThat(resolved.getSeriesName()).isEqualTo("Dragon Ball Super");
            assertThat(resolved.getSeriesNumber()).isEqualTo(24F);
            assertThat(resolved.getAuthor()).isEqualTo("Akira Toriyama");
            assertThat(resolved.getQuery()).isEqualTo("Dragon Ball Super");
            assertThat(resolved.getContentKind()).isEqualTo(DownloadContentKind.MANGA);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesWebtoonCanonicalSeriesFromSearchHtml() throws Exception {
        HttpServer server = htmlServer("/en/search", """
                <html><body>
                  <a href="/en/romance/lore-olympus/list?title_no=1320">
                    <span class="title">Lore Olympus</span>
                    <span class="author">Rachel Smythe</span>
                  </a>
                </body></html>
                """);
        server.start();
        try {
            DownloadCanonicalResolver resolver = resolver();
            resolver.openLibraryEnabled = false;
            resolver.googleBooksEnabled = false;
            resolver.mangaDexEnabled = false;
            resolver.webtoonsSearchUrlTemplates = baseUrl(server) + "/en/search?keyword={query}";

            DownloadSearchCriteria parsed = parser.enrich(DownloadSearchCriteria.builder()
                    .query("Lore Olympus episode 1")
                    .contentKind(DownloadContentKind.WEBTOON)
                    .build());

            DownloadSearchCriteria resolved = resolver.resolve(parsed);

            assertThat(resolved.getTitle()).isEqualTo("Lore Olympus");
            assertThat(resolved.getSeriesName()).isEqualTo("Lore Olympus");
            assertThat(resolved.getSeriesNumber()).isEqualTo(1F);
            assertThat(resolved.getAuthor()).isEqualTo("Rachel Smythe");
            assertThat(resolved.getQuery()).isEqualTo("Lore Olympus");
            assertThat(resolved.getContentKind()).isEqualTo(DownloadContentKind.WEBTOON);
        } finally {
            server.stop(0);
        }
    }

    private DownloadCanonicalResolver resolver() {
        DownloadCanonicalResolver resolver = new DownloadCanonicalResolver(HttpClient.newHttpClient(), new ObjectMapper());
        resolver.timeoutSeconds = 2;
        resolver.providerLimit = 3;
        return resolver;
    }

    private HttpServer jsonServer(String path, String body) throws IOException {
        return server(path, "application/json", body);
    }

    private HttpServer htmlServer(String path, String body) throws IOException {
        return server(path, "text/html", body);
    }

    private HttpServer server(String path, String contentType, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> respond(exchange, contentType, body));
        return server;
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
