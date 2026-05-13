package org.booklore.service.downloads;

import org.booklore.config.AppProperties;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.repository.DownloadJobRepository;
import org.booklore.repository.DownloadResultRepository;
import org.booklore.repository.DownloadSearchRepository;
import org.booklore.repository.DownloadSourceRepository;
import org.booklore.service.downloads.adapter.DownloadAdapterRegistry;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.executor.DownloadExecutorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DownloadPipelineManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void validateDownloadedFile_prefersActualPayloadExtensionOverAdvertisedFormat() throws Exception {
        Path downloaded = tempDir.resolve("Wakfu Manga - Tome 1.pdf");
        Files.writeString(downloaded, "%PDF-1.4");

        DownloadPipelineManager manager = new DownloadPipelineManager(
                new AppProperties(),
                mock(DownloadSourceRepository.class),
                mock(DownloadSearchRepository.class),
                mock(DownloadResultRepository.class),
                mock(DownloadJobRepository.class),
                mock(DownloadAdapterRegistry.class),
                mock(DownloadExecutorRegistry.class),
                mock(DownloadScoringService.class),
                mock(DownloadNamingService.class),
                mock(DownloadTargetResolver.class),
                mock(DownloadedCbxMetadataService.class),
                mock(BookdropDeliveryService.class),
                mock(DownloadQueryIntentParser.class),
                new ObjectMapper()
        );

        Method method = DownloadPipelineManager.class.getDeclaredMethod(
                "validateDownloadedFile",
                Path.class,
                NormalizedDownloadResult.class
        );
        method.setAccessible(true);

        DownloadFormat detected = (DownloadFormat) method.invoke(
                manager,
                downloaded,
                NormalizedDownloadResult.builder()
                        .title("Wakfu Manga - Tome 1")
                        .format(DownloadFormat.EPUB)
                        .build()
        );

        assertEquals(DownloadFormat.PDF, detected);
    }
}
