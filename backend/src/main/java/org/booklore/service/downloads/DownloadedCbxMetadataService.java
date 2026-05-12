package org.booklore.service.downloads;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.metadata.writer.ComicInfo;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class DownloadedCbxMetadataService {

    private static final int BUFFER_SIZE = 8192;
    private static final JAXBContext JAXB_CONTEXT = createJaxbContext();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public void embedIfApplicable(Path cbzFile, NormalizedDownloadResult result, DownloadFormat detectedFormat) {
        if (cbzFile == null || result == null || detectedFormat != DownloadFormat.CBZ || !hasUsefulMetadata(result)) {
            return;
        }
        try {
            if (hasComicInfo(cbzFile)) {
                log.debug("Downloaded CBZ already contains ComicInfo.xml, leaving it unchanged: {}", cbzFile.getFileName());
                return;
            }
            byte[] xml = buildComicInfoXml(result);
            Path tempFile = Files.createTempFile(cbzFile.getParent(), ".booklore-comicinfo-", ".cbz");
            boolean replaced = false;
            try {
                rebuildZipWithComicInfo(cbzFile, tempFile, xml);
                replaceAtomic(tempFile, cbzFile);
                replaced = true;
            } finally {
                if (!replaced) {
                    Files.deleteIfExists(tempFile);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to embed ComicInfo.xml into downloaded CBZ {}: {}", cbzFile, e.getMessage(), e);
        }
    }

    private boolean hasUsefulMetadata(NormalizedDownloadResult result) {
        return hasText(result.getTitle())
                || hasText(result.getSeriesName())
                || result.getSeriesNumber() != null
                || hasText(result.getLanguage())
                || (result.getAuthors() != null && !result.getAuthors().isEmpty())
                || hasText(result.getDetailsUrl())
                || hasText(result.getDownloadUrl());
    }

    private boolean hasComicInfo(Path cbzFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(cbzFile.toFile())) {
            return zipFile.stream().anyMatch(entry -> isComicInfoName(entry.getName()));
        }
    }

    private byte[] buildComicInfoXml(NormalizedDownloadResult result) throws Exception {
        ComicInfo comicInfo = new ComicInfo();
        comicInfo.setTitle(firstNonBlank(result.getTitle(), result.getSeriesName()));
        comicInfo.setSeries(result.getSeriesName());
        comicInfo.setNumber(formatNumber(result.getSeriesNumber()));
        comicInfo.setLanguageISO(result.getLanguage());
        comicInfo.setWriter(joinAuthors(result));
        comicInfo.setWeb(firstNonBlank(result.getDetailsUrl(), result.getDownloadUrl()));
        comicInfo.setFormat(comicFormat(result.getContentKind()));
        comicInfo.setManga(comicMangaFlag(result.getContentKind()));
        comicInfo.setSummary(rawText(result, "description"));
        comicInfo.setGenre(rawText(result, "genre"));
        comicInfo.setPageCount(rawInteger(result, "count"));
        comicInfo.setNotes(buildNotes(result));

        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        marshaller.marshal(comicInfo, output);
        return output.toByteArray();
    }

    private void rebuildZipWithComicInfo(Path sourceZip, Path targetZip, byte[] comicInfoXml) throws IOException {
        Set<String> copiedEntries = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(sourceZip.toFile());
             ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(targetZip))) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (isComicInfoName(entryName) || !isPathSafe(entryName) || !copiedEntries.add(entryName)) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entryName);
                if (entry.getTime() >= 0) {
                    copy.setTime(entry.getTime());
                }
                zipOutput.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (InputStream input = zipFile.getInputStream(entry)) {
                        copy(input, zipOutput);
                    }
                }
                zipOutput.closeEntry();
            }
            zipOutput.putNextEntry(new ZipEntry("ComicInfo.xml"));
            zipOutput.write(comicInfoXml);
            zipOutput.closeEntry();
        }
    }

    private void copy(InputStream input, ZipOutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private void replaceAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String comicFormat(DownloadContentKind contentKind) {
        if (contentKind == null) {
            return null;
        }
        return switch (contentKind) {
            case WEBTOON -> "Webcomic";
            case MANGA -> "Manga";
            case COMIC -> "Comic";
            case AUTO, BOOK -> null;
        };
    }

    private String comicMangaFlag(DownloadContentKind contentKind) {
        if (contentKind == DownloadContentKind.MANGA) {
            return "Yes";
        }
        if (contentKind == DownloadContentKind.WEBTOON || contentKind == DownloadContentKind.COMIC) {
            return "No";
        }
        return null;
    }

    private String buildNotes(NormalizedDownloadResult result) {
        StringBuilder notes = new StringBuilder("[BookLore:Source] Automated Downloads");
        if (result.getContentKind() != null) {
            notes.append("\n[BookLore:ContentKind] ").append(result.getContentKind());
        }
        if (hasText(result.getRawJson())) {
            notes.append("\n[BookLore:AcquisitionRaw] ").append(result.getRawJson());
        }
        return notes.toString();
    }

    private String rawText(NormalizedDownloadResult result, String key) {
        JsonNode raw = rawJson(result);
        if (raw == null) {
            return null;
        }
        String value = raw.path(key).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer rawInteger(NormalizedDownloadResult result, String key) {
        JsonNode raw = rawJson(result);
        if (raw == null) {
            return null;
        }
        int value = raw.path(key).asInt(0);
        return value > 0 ? value : null;
    }

    private JsonNode rawJson(NormalizedDownloadResult result) {
        try {
            if (result.getRawJson() == null || result.getRawJson().isBlank()) {
                return null;
            }
            JsonNode node = OBJECT_MAPPER.readTree(result.getRawJson());
            return node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String joinAuthors(NormalizedDownloadResult result) {
        if (result.getAuthors() == null || result.getAuthors().isEmpty()) {
            return null;
        }
        return String.join(", ", result.getAuthors());
    }

    private String formatNumber(Float value) {
        if (value == null) {
            return null;
        }
        if (value % 1 == 0) {
            return Integer.toString(value.intValue());
        }
        return String.format(Locale.ROOT, "%s", value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isComicInfoName(String entryName) {
        if (entryName == null) {
            return false;
        }
        String normalized = entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
        return "comicinfo.xml".equals(normalized) || normalized.endsWith("/comicinfo.xml");
    }

    private static boolean isPathSafe(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("\0")) {
            return false;
        }
        for (String component : normalized.split("/", -1)) {
            if ("..".equals(component)) {
                return false;
            }
        }
        return true;
    }

    private static JAXBContext createJaxbContext() {
        try {
            return JAXBContext.newInstance(ComicInfo.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize ComicInfo JAXB context", e);
        }
    }
}
