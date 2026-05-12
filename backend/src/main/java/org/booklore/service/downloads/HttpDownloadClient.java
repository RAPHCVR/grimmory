package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import org.booklore.service.downloads.dto.DownloadProgressSink;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HttpDownloadClient {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MIN_SUCCESS_STATUS = 200;
    private static final int MAX_SUCCESS_STATUS = 299;

    private final HttpClient httpClient;

    public Path downloadToFile(String url, Path targetPartFile, DownloadProgressSink progressSink) {
        return downloadToFile(url, targetPartFile, Map.of(), progressSink);
    }

    public Path downloadToFile(String url,
                               Path targetPartFile,
                               Map<String, String> headers,
                               DownloadProgressSink progressSink) {
        try {
            Files.createDirectories(targetPartFile.getParent());
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "BookLore-Downloads")
                    .GET();
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                        builder.setHeader(name, value);
                    }
                });
            }
            HttpRequest request = builder.build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < MIN_SUCCESS_STATUS || response.statusCode() > MAX_SUCCESS_STATUS) {
                throw new DownloadSourceException("Download failed with HTTP status " + response.statusCode());
            }

            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(targetPartFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long copied = 0L;
                int lastPercent = -1;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    copied += read;
                    if (contentLength > 0 && progressSink != null) {
                        int percent = (int) Math.min(99, (copied * 100) / contentLength);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            progressSink.onProgress(percent);
                        }
                    }
                }
            }
            if (progressSink != null) {
                progressSink.onProgress(100);
            }
            return targetPartFile;
        } catch (IOException e) {
            throw new DownloadSourceException("Failed to write downloaded file: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Download interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new DownloadSourceException("Invalid download URL: " + url, e);
        }
    }
}
