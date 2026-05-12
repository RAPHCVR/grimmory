package org.booklore.service.downloads;

import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.metadata.extractor.CbxMetadataExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadedCbxMetadataServiceTest {

    @TempDir
    Path tempDir;

    private final DownloadedCbxMetadataService service = new DownloadedCbxMetadataService();
    private final CbxMetadataExtractor extractor = new CbxMetadataExtractor();

    @Test
    void embedIfApplicable_addsComicInfoXmlThatBookLoreCanExtract() throws Exception {
        Path cbz = tempDir.resolve("chapter.cbz");
        createCbz(cbz);
        String url = "https://www.webtoons.com/fr/fantasy/tower-of-god/saison-3-ep-235/viewer?title_no=1832&episode_no=652";
        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Saison 3 Ep 235")
                .seriesName("Tower of God")
                .seriesNumber(235f)
                .language("fr")
                .authors(List.of("SIU"))
                .contentKind(DownloadContentKind.WEBTOON)
                .downloadUrl(url)
                .detailsUrl(url)
                .rawJson("{\"description\":\"Tower description\",\"genre\":\"fantasy\",\"count\":161}")
                .build();

        service.embedIfApplicable(cbz, result, DownloadFormat.CBZ);

        assertTrue(containsComicInfo(cbz));
        var metadata = extractor.extractMetadata(cbz.toFile());
        assertEquals("Saison 3 Ep 235", metadata.getTitle());
        assertEquals("Tower of God", metadata.getSeriesName());
        assertEquals(235f, metadata.getSeriesNumber());
        assertEquals("fr", metadata.getLanguage());
        assertEquals("Tower description", metadata.getDescription());
        assertEquals(161, metadata.getPageCount());
        assertEquals(List.of("SIU"), metadata.getAuthors());
        assertTrue(metadata.getCategories().contains("fantasy"));
        assertEquals(url, metadata.getExternalUrl());
        assertNotNull(metadata.getComicMetadata());
        assertEquals("Webcomic", metadata.getComicMetadata().getFormat());
        assertEquals(url, metadata.getComicMetadata().getWebLink());
    }

    private void createCbz(Path cbz) throws Exception {
        Files.createDirectories(cbz.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            zip.putNextEntry(new ZipEntry("001.jpg"));
            zip.write("page".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private boolean containsComicInfo(Path cbz) throws Exception {
        try (ZipFile zipFile = new ZipFile(cbz.toFile())) {
            return zipFile.stream().anyMatch(entry -> "ComicInfo.xml".equals(entry.getName()));
        }
    }
}
