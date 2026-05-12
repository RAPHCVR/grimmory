package org.booklore.service.downloads;

import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.executor.DownloadExecutionRequest;
import org.booklore.service.downloads.executor.GalleryDlDownloadExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryDlDownloadExecutorTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void download_runsConfiguredBinaryAndReturnsGeneratedCbz() throws Exception {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("gallery-dl")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson(objectMapper.writeValueAsString(Map.of(
                        "galleryDl", Map.of(
                                "binaryPath", fakeGalleryDlCommand().binaryPath(),
                                "timeoutMinutes", 1,
                                "extraArgs", fakeGalleryDlCommand().extraArgs()
                        )
                )))
                .build();
        NormalizedDownloadResult result = NormalizedDownloadResult.builder()
                .title("Let's Play")
                .contentKind(DownloadContentKind.WEBTOON)
                .format(DownloadFormat.CBZ)
                .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                .downloadUrl("https://www.webtoons.com/en/canvas/lets-play/list?title_no=82982")
                .build();
        GalleryDlDownloadExecutor executor = new GalleryDlDownloadExecutor(new DownloadSourceConfigReader(objectMapper));
        List<Integer> progress = new ArrayList<>();

        Path downloaded = executor.download(DownloadExecutionRequest.builder()
                .source(source)
                .result(result)
                .stagingDir(tempDir.resolve("staging"))
                .targetPartFile(tempDir.resolve("staging").resolve("ignored.part"))
                .build(), progress::add);

        assertTrue(downloaded.getFileName().toString().endsWith(".cbz"));
        assertTrue(Files.size(downloaded) > 0);
        assertEquals(List.of("001.jpg"), zipEntries(downloaded));
        assertEquals(100, progress.getLast());
    }

    private String javaBinary() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private FakeCommand fakeGalleryDlCommand() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return new FakeCommand(javaBinary(), List.of("-cp", System.getProperty("java.class.path"), FakeGalleryDlProcess.class.getName()));
        }

        Path script = tempDir.resolve("fake-gallery-dl.ps1");
        Files.writeString(script, """
                $dest = $null
                for ($i = 0; $i -lt $args.Count; $i++) {
                  if ($args[$i] -eq '--destination' -and $i + 1 -lt $args.Count) {
                    $dest = $args[$i + 1]
                  }
                }
                if (-not $dest) {
                  Write-Error 'missing --destination'
                  exit 2
                }
                Write-Output '10% preparing'
                $folder = Join-Path $dest 'Webtoon'
                New-Item -ItemType Directory -Force -Path $folder | Out-Null
                $tmp = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
                New-Item -ItemType Directory -Force -Path $tmp | Out-Null
                Set-Content -Encoding ASCII -Path (Join-Path $tmp '001.jpg') -Value 'page'
                Add-Type -AssemblyName System.IO.Compression.FileSystem
                $cbz = Join-Path $folder 'Chapter 001.cbz'
                if (Test-Path $cbz) { Remove-Item -Force $cbz }
                [IO.Compression.ZipFile]::CreateFromDirectory($tmp, $cbz)
                Remove-Item -Recurse -Force $tmp
                Write-Output '100% done'
                """, StandardCharsets.UTF_8);
        String powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        return new FakeCommand(powershell, List.of("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
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

    public static class FakeGalleryDlProcess {
        public static void main(String[] args) throws Exception {
            Path destBase = null;
            for (int i = 0; i < args.length - 1; i++) {
                if ("--destination".equals(args[i])) {
                    destBase = Path.of(args[i + 1]);
                    break;
                }
            }
            if (destBase == null) {
                System.err.println("missing --destination");
                System.exit(2);
            }

            System.out.println("10% preparing");
            Path cbz = destBase.resolve("Webtoon").resolve("Chapter 001.cbz");
            Files.createDirectories(cbz.getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
                zip.putNextEntry(new ZipEntry("001.jpg"));
                zip.write("page".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            System.out.println("100% done");
        }
    }

    private record FakeCommand(String binaryPath, List<String> extraArgs) {
    }
}
