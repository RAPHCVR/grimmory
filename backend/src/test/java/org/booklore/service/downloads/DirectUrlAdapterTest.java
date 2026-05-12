package org.booklore.service.downloads;

import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.impl.DirectUrlAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirectUrlAdapterTest {

    private final DirectUrlAdapter adapter = new DirectUrlAdapter(new ObjectMapper());

    @TempDir
    Path tempDir;

    @Test
    void search_directCbzUrl_returnsDirectFileResult() {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("direct")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson("{}")
                .build();
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl("https://example.test/series/chapter.cbz")
                .title("Chapter")
                .contentKind(DownloadContentKind.COMIC)
                .build();

        var results = adapter.search(source, criteria);

        assertEquals(1, results.size());
        assertEquals(DownloadAcquisitionType.DIRECT_FILE, results.getFirst().getAcquisitionType());
        assertEquals(DownloadFormat.CBZ, results.getFirst().getFormat());
        assertFalse(results.getFirst().isRequiresFlareSolverr());
    }

    @Test
    void search_magnetUrlWithFlareSolverr_marksTorrentAndFlareSolverr() {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("direct")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson("{\"useFlareSolverr\":true}")
                .build();
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
                .title("Torrent Result")
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        var results = adapter.search(source, criteria);

        assertEquals(1, results.size());
        assertEquals(DownloadAcquisitionType.TORRENT, results.getFirst().getAcquisitionType());
        assertEquals(DownloadFormat.UNKNOWN, results.getFirst().getFormat());
        assertTrue(results.getFirst().isRequiresFlareSolverr());
    }

    @Test
    void search_directUrlWithGalleryDlConfig_returnsGalleryDlResult() {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("webtoon")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson("{\"galleryDl\":{\"enabled\":true,\"binaryPath\":\"gallery-dl\",\"metadataProbeEnabled\":false}}")
                .build();
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl("https://www.webtoons.com/en/canvas/lets-play/list?title_no=82982")
                .title("Let's Play")
                .contentKind(DownloadContentKind.WEBTOON)
                .build();

        var results = adapter.search(source, criteria);

        assertEquals(1, results.size());
        assertEquals(DownloadAcquisitionType.CLI_GALLERY_DL, results.getFirst().getAcquisitionType());
        assertEquals(DownloadFormat.CBZ, results.getFirst().getFormat());
    }

    @Test
    void search_webtoonsViewerUrlWithoutManualMetadata_infersSeriesEpisodeAndContentKind() {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("webtoon")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson("{\"galleryDl\":{\"enabled\":true,\"binaryPath\":\"gallery-dl\",\"metadataProbeEnabled\":false}}")
                .build();
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl("https://www.webtoons.com/fr/fantasy/tower-of-god/saison-3-ep-235/viewer?title_no=1832&episode_no=652")
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        var results = adapter.search(source, criteria);

        assertEquals(1, results.size());
        var result = results.getFirst();
        assertEquals(DownloadAcquisitionType.CLI_GALLERY_DL, result.getAcquisitionType());
        assertEquals(DownloadFormat.CBZ, result.getFormat());
        assertEquals(DownloadContentKind.WEBTOON, result.getContentKind());
        assertEquals("Tower of God", result.getSeriesName());
        assertEquals("Saison 3 Ep 235", result.getTitle());
        assertEquals(235f, result.getSeriesNumber());
        assertEquals("fr", result.getLanguage());
        assertNotNull(result.getRawJson());
        assertTrue(result.getRawJson().contains("\"titleNo\":\"1832\""));
    }

    @Test
    void search_galleryDlMetadataProbe_prefersExtractorMetadata() throws Exception {
        FakeCommand fakeCommand = fakeGalleryDlCommand();
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("webtoon")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson(new ObjectMapper().writeValueAsString(java.util.Map.of(
                        "galleryDl", java.util.Map.of(
                                "enabled", true,
                                "binaryPath", fakeCommand.binaryPath(),
                                "extraArgs", fakeCommand.extraArgs(),
                                "metadataProbeTimeoutSeconds", 5
                        )
                )))
                .build();
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl("https://www.webtoons.com/fr/fantasy/tower-of-god/saison-3-ep-235/viewer?title_no=1832&episode_no=652")
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        var result = adapter.search(source, criteria).getFirst();

        assertEquals("Tower of God", result.getSeriesName());
        assertEquals("[Saison 3] Ep. 235", result.getTitle());
        assertEquals(235f, result.getSeriesNumber());
        assertEquals("fr", result.getLanguage());
        assertEquals(List.of("SIU"), result.getAuthors());
        assertEquals(DownloadContentKind.WEBTOON, result.getContentKind());
        assertTrue(result.getRawJson().contains("\"description\""));
    }

    private FakeCommand fakeGalleryDlCommand() throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Path script = tempDir.resolve("fake-gallery-dl-probe.ps1");
            Files.writeString(script, """
                    Write-Output '[[2,{"author_name":"SIU","category":"webtoons","comic":"tower-of-god","comic_name":"Tower of God","count":161,"description":"Tower description","episode":"652","episode_name":"[Saison 3] Ep. 235","episode_no":"652","genre":"fantasy","lang":"fr","language":"French","title":"Tower of God - [Saison 3] Ep. 235","title_no":"1832"}]]'
                    """, StandardCharsets.UTF_8);
            String powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
            return new FakeCommand(powershell, List.of("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        }
        return new FakeCommand(javaBinary(), List.of("-cp", System.getProperty("java.class.path"), FakeGalleryDlProbe.class.getName()));
    }

    private String javaBinary() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static class FakeGalleryDlProbe {
        public static void main(String[] args) {
            System.out.println("""
                    [[2,{"author_name":"SIU","category":"webtoons","comic":"tower-of-god","comic_name":"Tower of God","count":161,"description":"Tower description","episode":"652","episode_name":"[Saison 3] Ep. 235","episode_no":"652","genre":"fantasy","lang":"fr","language":"French","title":"Tower of God - [Saison 3] Ep. 235","title_no":"1832"}]]
                    """);
        }
    }

    private record FakeCommand(String binaryPath, List<String> extraArgs) {
    }
}
