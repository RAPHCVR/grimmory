package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.WebtoonScraperDownloadExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebtoonScraperDownloadExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void download_writesOrderedCbzAndAppliesManifestReferer() throws Exception {
        List<String> receivedReferers = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page-1.webp", exchange -> {
            receivedReferers.add(exchange.getRequestHeaders().getFirst("Referer"));
            byte[] body = "first-image".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "image/webp");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/page-2.jpg", exchange -> {
            receivedReferers.add(exchange.getRequestHeaders().getFirst("Referer"));
            byte[] body = "second-image".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String rawJson = """
                    {
                      "imageUrls": ["%s/page-1.webp", "%s/page-2.jpg"],
                      "referer": "https://authorized.example/",
                      "parallelism": 2,
                      "timeoutSeconds": 5
                    }
                    """.formatted(baseUrl, baseUrl);

            NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                    .title("Chapter 1")
                    .format(DownloadFormat.CBZ)
                    .acquisitionType(DownloadAcquisitionType.IMAGE_SEQUENCE_CBZ)
                    .rawJson(rawJson)
                    .build();
            Path targetPartFile = tempDir.resolve("chapter.part");
            WebtoonScraperDownloadExecutor executor = new WebtoonScraperDownloadExecutor(HttpClient.newHttpClient(), new ObjectMapper());

            List<Integer> progress = new ArrayList<>();
            Path downloaded = executor.download(DownloadExecutionRequest.builder()
                    .result(result)
                    .stagingDir(tempDir)
                    .targetPartFile(targetPartFile)
                    .build(), progress::add);

            assertEquals(targetPartFile, downloaded);
            assertTrue(Files.size(targetPartFile) > 0);
            assertEquals(List.of("https://authorized.example/", "https://authorized.example/"), receivedReferers);
            assertEquals(100, progress.getLast());
            assertEquals(List.of("001.webp", "002.jpg"), zipEntries(targetPartFile));
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
