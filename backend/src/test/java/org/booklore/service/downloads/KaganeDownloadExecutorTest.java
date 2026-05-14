package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.client.FlareSolverrClient;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.KaganeDownloadExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KaganeDownloadExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void download_rendersChapterWithFlareSolverrAndDownloadsImagesWithSolvedHeaders() throws Exception {
        AtomicReference<String> flareCommand = new AtomicReference<>();
        AtomicReference<String> flareUrl = new AtomicReference<>();
        List<String> imageUserAgents = new CopyOnWriteArrayList<>();
        List<String> imageCookies = new CopyOnWriteArrayList<>();
        List<String> imageReferers = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String chapterUrl = "https://kagane.example/series/solo-leveling/chapter-1/";

        server.createContext("/v1", exchange -> {
            var body = objectMapper.readTree(exchange.getRequestBody());
            flareCommand.set(body.path("cmd").asText());
            flareUrl.set(body.path("url").asText());

            String html = """
                    <html>
                      <body>
                        <div class="reading-content">
                          <img data-src="%s/images/page-1">
                          <div class="page-break"><img src="%s/images/page-2.png"></div>
                          <img src="%s/images/page-1">
                        </div>
                      </body>
                    </html>
                    """.formatted(baseUrl, baseUrl, baseUrl);
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "status", "ok",
                    "solution", Map.of(
                            "response", html,
                            "userAgent", "Mozilla/5.0 KaganeTest",
                            "cookies", List.of(
                                    Map.of("name", "cf_clearance", "value", "solved"),
                                    Map.of("name", "session", "value", "abc")
                            )
                    )
            ));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/images/page-1", exchange -> {
            imageUserAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            imageCookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            imageReferers.add(exchange.getRequestHeaders().getFirst("Referer"));
            byte[] response = "first-image".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/images/page-2.png", exchange -> {
            imageUserAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            imageCookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            imageReferers.add(exchange.getRequestHeaders().getFirst("Referer"));
            byte[] response = "second-image".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Kagane")
                    .type(DownloadSourceType.DIRECT_URL)
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "kagane", Map.of("enabled", true),
                            "flareSolverr", Map.of("baseUrl", baseUrl, "maxTimeoutMs", 5000)
                    )))
                    .build();
            NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                    .title("Chapter 1")
                    .contentKind(DownloadContentKind.WEBTOON)
                    .format(DownloadFormat.CBZ)
                    .downloadUrl(chapterUrl)
                    .acquisitionType(DownloadAcquisitionType.KAGANE_CHAPTER)
                    .build();
            Path targetPartFile = tempDir.resolve("solo-leveling.part");
            DownloadSourceConfigReader configReader = new DownloadSourceConfigReader(objectMapper);
            KaganeDownloadExecutor executor = new KaganeDownloadExecutor(
                    new FlareSolverrClient(HttpClient.newHttpClient(), objectMapper, configReader),
                    HttpClient.newHttpClient(),
                    configReader
            );

            List<Integer> progress = new ArrayList<>();
            Path downloaded = executor.download(DownloadExecutionRequest.builder()
                    .source(source)
                    .result(result)
                    .stagingDir(tempDir)
                    .targetPartFile(targetPartFile)
                    .build(), progress::add);

            assertEquals(targetPartFile, downloaded);
            assertEquals("request.get", flareCommand.get());
            assertEquals(chapterUrl, flareUrl.get());
            assertEquals(List.of("Mozilla/5.0 KaganeTest", "Mozilla/5.0 KaganeTest"), imageUserAgents);
            assertEquals(List.of("cf_clearance=solved; session=abc", "cf_clearance=solved; session=abc"), imageCookies);
            assertEquals(List.of(chapterUrl, chapterUrl), imageReferers);
            assertEquals(List.of("001.jpg", "002.png"), zipEntries(targetPartFile));
            assertFalse(Files.exists(tempDir.resolve("kagane-pages")));
            assertEquals(100, progress.getLast());
        } finally {
            server.stop(0);
        }
    }

    private List<String> zipEntries(Path cbzFile) throws Exception {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(cbzFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }
}
