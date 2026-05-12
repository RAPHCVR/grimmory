package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.service.downloads.client.QbittorrentClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QbittorrentClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void addUrl_acceptsQbittorrent204LoginWithSidCookie() throws Exception {
        AtomicReference<String> loginBody = new AtomicReference<>();
        AtomicReference<String> addCookie = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/auth/login", exchange -> {
            loginBody.set(new String(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().add("Set-Cookie", "QBT_SID_8080=test-session; HttpOnly; path=/");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/api/v2/torrents/add", exchange -> {
            addCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "Ok.".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            QbittorrentClient client = new QbittorrentClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    new DownloadSourceConfigReader(objectMapper)
            );
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "qbittorrent", Map.of(
                                    "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(),
                                    "username", "admin",
                                    "password", "adminadmin",
                                    "category", "grimmory",
                                    "tags", "grimmory"
                            )
                    )))
                    .build();

            QbittorrentClient.QbittorrentConfig config = client.readConfig(source);
            QbittorrentClient.SubmittedTorrent submitted = client.addUrl(
                    config,
                    "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                    "/bookdrop/.downloads/42",
                    "grimmory-job-42"
            );

            assertEquals("0123456789abcdef0123456789abcdef01234567", submitted.hash());
            assertTrue(loginBody.get().contains("username=admin"));
            assertTrue(loginBody.get().contains("password=adminadmin"));
            assertEquals("QBT_SID_8080=test-session", addCookie.get());
        } finally {
            server.stop(0);
        }
    }
}
