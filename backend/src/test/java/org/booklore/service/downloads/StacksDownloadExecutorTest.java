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

    @Test
    void download_usesNativeStacksQueueApiAndCopiesCompletedFileIntoStaging() throws Exception {
        Path bookdrop = tempDir.resolve("bookdrop");
        Path stagingDir = bookdrop.resolve(".downloads").resolve("42");
        Files.createDirectories(stagingDir);
        Path stacksOutput = bookdrop.resolve("Les Fourmis.epub");
        Files.writeString(stacksOutput, "epub");
        AtomicReference<String> submitBody = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/queue/add", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            submitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"success":true,"message":"Added to queue","md5":"abc123"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/status", exchange -> {
            String responseJson = objectMapper.writeValueAsString(Map.of(
                    "recent_history", List.of(Map.of(
                            "md5", "abc123",
                            "success", true,
                            "status", "completed",
                            "filepath", "/opt/stacks/download/Les Fourmis.epub"
                    )),
                    "queue", List.of(),
                    "current_downloads", List.of()
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
                                    "baseUrl", baseUrl,
                                    "apiKey", "secret",
                                    "localDownloadRoot", bookdrop.toString(),
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

            assertEquals("secret", apiKeyHeader.get());
            assertTrue(submitBody.get().contains("\"md5\":\"abc123\""));
            assertTrue(result.startsWith(stagingDir));
            assertTrue(Files.exists(result));
            assertTrue(Files.exists(stacksOutput));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void download_whenStacksStatusIsAdminOnlyPollsSharedFolderAndCopiesCompletedFileIntoStaging() throws Exception {
        Path bookdrop = tempDir.resolve("bookdrop");
        Path stagingDir = bookdrop.resolve(".downloads").resolve("42");
        Files.createDirectories(stagingDir);
        Path stacksOutput = bookdrop.resolve("Les fourmis_116121562.epub");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/queue/add", exchange -> {
            byte[] response = """
                    {"success":true,"message":"Added to queue","md5":"6fc83a82e765e3808aa55102b0894275"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/status", exchange -> {
            Files.writeString(stacksOutput, "epub");
            byte[] response = """
                    {"error":"Insufficient permissions. Admin access required.","success":false}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, response.length);
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
                                    "baseUrl", baseUrl,
                                    "apiKey", "downloader-secret",
                                    "localDownloadRoot", bookdrop.toString(),
                                    "pollIntervalSeconds", 1,
                                    "timeoutMinutes", 1,
                                    "requestTimeoutSeconds", 5
                            )
                    )))
                    .build();
            StacksDownloadExecutor executor = new StacksDownloadExecutor(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    new DownloadSourceConfigReader(objectMapper),
                    mock(DownloadJobRepository.class)
            );

            Path result = executor.download(DownloadExecutionRequest.builder()
                    .job(DownloadJobEntity.builder().id(42L).build())
                    .source(source)
                    .result(NormalizedDownloadResult.builder()
                            .sourceResultId("6fc83a82e765e3808aa55102b0894275")
                            .title("Les Fourmis (Les Fourmis, Tome 1)")
                            .authors(List.of("Bernard Werber"))
                            .format(DownloadFormat.EPUB)
                            .contentKind(DownloadContentKind.BOOK)
                            .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                            .rawJson("{\"md5\":\"6fc83a82e765e3808aa55102b0894275\"}")
                            .build())
                    .stagingDir(stagingDir)
                    .targetPartFile(stagingDir.resolve("download.part"))
                    .build(), ignored -> {
                    });

            assertTrue(result.startsWith(stagingDir));
            assertTrue(Files.exists(result));
            assertTrue(Files.exists(stacksOutput));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void download_whenStacksAlreadyDownloadedUsesCachedPayload() throws Exception {
        Path bookdrop = tempDir.resolve("bookdrop");
        Path stagingDir = bookdrop.resolve(".downloads").resolve("42");
        Files.createDirectories(stagingDir);
        Path stacksOutput = bookdrop.resolve("Bernard Werber - Les Fourmis.epub");
        Files.writeString(stacksOutput, "epub");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/queue/add", exchange -> {
            byte[] response = """
                    {"success":false,"message":"Already downloaded successfully","md5":"abc123"}
                    """.getBytes(StandardCharsets.UTF_8);
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
                                    "baseUrl", baseUrl,
                                    "apiKey", "downloader-secret",
                                    "localDownloadRoot", bookdrop.toString(),
                                    "pollIntervalSeconds", 1,
                                    "timeoutMinutes", 1,
                                    "requestTimeoutSeconds", 5
                            )
                    )))
                    .build();
            StacksDownloadExecutor executor = new StacksDownloadExecutor(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    new DownloadSourceConfigReader(objectMapper),
                    mock(DownloadJobRepository.class)
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

            assertTrue(result.startsWith(stagingDir));
            assertTrue(Files.exists(result));
            assertTrue(Files.exists(stacksOutput));
        } finally {
            server.stop(0);
        }
    }
}
