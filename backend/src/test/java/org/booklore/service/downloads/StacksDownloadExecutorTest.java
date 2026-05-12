package org.booklore.service.downloads;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.entity.DownloadJobEntity;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.StacksDownloadExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StacksDownloadExecutorTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void download_postsToStacksPollsStatusAndMapsRemoteStagingPathToLocalFile() throws Exception {
        Path stagingDir = tempDir.resolve("staging");
        Files.createDirectories(stagingDir);
        Path completedFile = stagingDir.resolve("book.epub");
        AtomicReference<String> submitBody = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/download", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            submitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"taskId":"task-1","status":"queued"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/download/task-1", exchange -> {
            Files.writeString(completedFile, "epub");
            String responseJson = objectMapper.writeValueAsString(Map.of(
                    "status", "completed",
                    "progress", 100,
                    "filePath", "/shared/bookdrop/.downloads/42/book.epub"
            ));
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DownloadSourceEntity source = DownloadSourceEntity.builder()
                    .name("Stacks")
                    .configJson(objectMapper.writeValueAsString(Map.of(
                            "stacks", Map.of(
                                    "apiUrl", baseUrl + "/api/download",
                                    "apiKey", "secret",
                                    "authorizationScheme", "Bearer",
                                    "remoteStagingPath", "/shared/bookdrop/.downloads/{jobId}",
                                    "statusUrlTemplate", baseUrl + "/api/download/{taskId}",
                                    "pollIntervalSeconds", 1,
                                    "timeoutMinutes", 1,
                                    "requestTimeoutSeconds", 5
                            )
                    )))
                    .build();
            DownloadJobRepository jobRepository = mock(DownloadJobRepository.class);
            StacksDownloadExecutor executor = new StacksDownloadExecutor(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    new DownloadSourceConfigReader(objectMapper),
                    jobRepository
            );

            Path result = executor.download(DownloadExecutionRequest.builder()
                    .job(DownloadJobEntity.builder().id(42L).build())
                    .source(source)
                    .result(NormalizedDownloadResult.builder()
                            .sourceResultId("abc123")
                            .title("Les Fourmis")
                            .authors(List.of("Bernard Werber"))
                            .format(DownloadFormat.EPUB)
                            .contentKind(DownloadContentKind.BOOK)
                            .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                            .rawJson("{\"md5\":\"abc123\"}")
                            .build())
                    .stagingDir(stagingDir)
                    .targetPartFile(stagingDir.resolve("download.part"))
                    .build(), ignored -> {
            });

            assertEquals(completedFile.toAbsolutePath().normalize(), result);
            assertTrue(Files.exists(result));
            assertEquals("Bearer secret", authHeader.get());
            assertTrue(submitBody.get().contains("\"md5\":\"abc123\""));
            assertTrue(submitBody.get().contains("/shared/bookdrop/.downloads/42"));
            verify(jobRepository).save(any(DownloadJobEntity.class));
        } finally {
            server.stop(0);
        }
    }
}
