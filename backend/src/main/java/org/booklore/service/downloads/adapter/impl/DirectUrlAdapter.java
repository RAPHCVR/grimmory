package org.booklore.service.downloads.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.DownloadContentClassifier;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DirectUrlAdapter implements DownloadSourceAdapter {

    private static final Pattern EPISODE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:^|[-_\\[\\] ])(?:ep|episode|chap|chapter|ch)(?:\\.|[-_ ])*0*(\\d+(?:\\.\\d+)?)(?:$|[-_\\] ])"
    );
    private static final String DEFAULT_GALLERY_DL_BINARY = "gallery-dl";

    private final ObjectMapper objectMapper;
    private final DownloadContentClassifier contentClassifier;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.DIRECT_URL;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        if (criteria.getDirectUrl() == null || criteria.getDirectUrl().isBlank()) {
            return List.of();
        }
        String fileName = extractFilename(criteria.getDirectUrl());
        DownloadAcquisitionType acquisitionType = inferAcquisitionType(source, criteria.getDirectUrl());
        UrlMetadata urlMetadata = inferUrlMetadata(criteria.getDirectUrl(), acquisitionType).orElse(UrlMetadata.empty());
        if (acquisitionType == DownloadAcquisitionType.CLI_GALLERY_DL) {
            UrlMetadata fallbackMetadata = urlMetadata;
            urlMetadata = probeGalleryDlMetadata(source, criteria.getDirectUrl())
                    .map(probed -> probed.withFallback(fallbackMetadata))
                    .orElse(urlMetadata);
        }
        DownloadFormat format = acquisitionType == DownloadAcquisitionType.CLI_GALLERY_DL
                ? DownloadFormat.CBZ
                : DownloadFormat.fromFileName(fileName).orElse(DownloadFormat.UNKNOWN);
        String title = firstNonBlank(criteria.getTitle(), urlMetadata.title(), stripExtension(fileName));
        DownloadContentKind inferredContentKind = firstNonNull(
                urlMetadata.contentKind(),
                contentClassifier.infer(sourceType(), source.getName(), title, urlMetadata.seriesName(), criteria.getDirectUrl(), criteria.getDirectUrl(), format, acquisitionType, urlMetadata.rawJson())
        );
        DownloadContentKind contentKind = contentClassifier.resolve(
                criteria.getContentKind(),
                inferredContentKind,
                format.isArchiveComicFormat() ? DownloadContentKind.COMIC : DownloadContentKind.BOOK
        );
        return List.of(NormalizedDownloadResult.builder()
                .sourceResultId(criteria.getDirectUrl())
                .title(title)
                .authors(resolveAuthors(criteria, urlMetadata))
                .seriesName(firstNonBlank(criteria.getSeriesName(), urlMetadata.seriesName()))
                .seriesNumber(criteria.getSeriesNumber() != null ? criteria.getSeriesNumber() : urlMetadata.seriesNumber())
                .isbn(criteria.getIsbn())
                .language(urlMetadata.language())
                .contentKind(contentKind)
                .format(format)
                .downloadUrl(criteria.getDirectUrl())
                .detailsUrl(criteria.getDirectUrl())
                .requiresFlareSolverr(useFlareSolverr(source))
                .acquisitionType(acquisitionType)
                .rawJson(urlMetadata.rawJson())
                .build());
    }

    private Optional<UrlMetadata> probeGalleryDlMetadata(DownloadSourceEntity source, String directUrl) {
        GalleryDlProbeConfig config = readGalleryDlProbeConfig(source);
        if (!config.enabled()) {
            return Optional.empty();
        }

        List<String> command = new ArrayList<>();
        command.add(config.binaryPath());
        command.addAll(config.extraArgs());
        command.add("--dump-json");
        command.add("--no-download");
        command.add(directUrl);

        ExecutorService outputExecutor = Executors.newSingleThreadExecutor();
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readProcessOutput(process), outputExecutor);
            boolean completed = process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                destroyProcessTree(process);
                return Optional.empty();
            }
            String body = output.get(3, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || body == null || body.isBlank()) {
                return Optional.empty();
            }
            return parseGalleryDlDump(body);
        } catch (Exception ignored) {
            return Optional.empty();
        } finally {
            outputExecutor.shutdownNow();
        }
    }

    private Optional<UrlMetadata> parseGalleryDlDump(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return findGalleryDlMetadata(root).map(this::toGalleryDlMetadata);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<JsonNode> findGalleryDlMetadata(JsonNode root) {
        if (root == null) {
            return Optional.empty();
        }
        if (root.isObject()) {
            return Optional.of(root);
        }
        if (!root.isArray()) {
            return Optional.empty();
        }
        for (JsonNode event : root) {
            if (!event.isArray()) {
                continue;
            }
            if (event.size() >= 2 && event.get(1).isObject()) {
                return Optional.of(event.get(1));
            }
            if (event.size() >= 3 && event.get(2).isObject()) {
                return Optional.of(event.get(2));
            }
        }
        return Optional.empty();
    }

    private UrlMetadata toGalleryDlMetadata(JsonNode metadata) {
        String seriesName = firstNonBlank(text(metadata, "comic_name"), titleCaseSlug(text(metadata, "comic")));
        String rawTitle = firstNonBlank(text(metadata, "episode_name"), text(metadata, "chapter_name"), text(metadata, "title"));
        String title = firstNonBlank(stripSeriesPrefix(rawTitle, seriesName), seriesName);
        Float seriesNumber = firstNonNull(
                parseEpisodeNumber(rawTitle),
                parseFloat(text(metadata, "chapter")),
                parseFloat(text(metadata, "volume"))
        );
        DownloadContentKind contentKind = galleryDlContentKind(metadata);
        String author = text(metadata, "author_name", "author", "username");

        return new UrlMetadata(
                title,
                seriesName,
                seriesNumber,
                firstNonBlank(text(metadata, "lang"), cleanLanguage(text(metadata, "language"))),
                contentKind,
                author == null ? List.of() : List.of(author),
                metadata.toString()
        );
    }

    private Optional<UrlMetadata> inferUrlMetadata(String directUrl, DownloadAcquisitionType acquisitionType) {
        if (acquisitionType != DownloadAcquisitionType.CLI_GALLERY_DL) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(directUrl);
            if (isWebtoonsUrl(uri)) {
                return Optional.of(parseWebtoonsMetadata(uri, directUrl));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private UrlMetadata parseWebtoonsMetadata(URI uri, String directUrl) {
        List<String> segments = Arrays.stream(uri.getPath().split("/"))
                .filter(segment -> segment != null && !segment.isBlank())
                .map(this::decode)
                .toList();
        Map<String, String> query = parseQuery(uri.getRawQuery());

        String language = segments.size() >= 1 ? cleanLanguage(segments.getFirst()) : null;
        String genreOrSection = segments.size() >= 2 ? segments.get(1) : null;
        String seriesSlug = segments.size() >= 3 ? segments.get(2) : null;
        String seriesName = titleCaseSlug(seriesSlug);
        String episodeSlug = webtoonsEpisodeSlug(segments);
        String episodeTitle = titleCaseSlug(episodeSlug);
        Float seriesNumber = firstNonNull(parseEpisodeNumber(episodeSlug), parseFloat(query.get("episode_no")));
        String title = firstNonBlank(episodeTitle, seriesName, stripExtension(extractFilename(directUrl)));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("provider", "webtoons-url");
        raw.put("url", directUrl);
        raw.put("language", language);
        raw.put("section", genreOrSection);
        raw.put("seriesSlug", seriesSlug);
        raw.put("seriesName", seriesName);
        raw.put("episodeSlug", episodeSlug);
        raw.put("titleNo", query.get("title_no"));
        raw.put("episodeNo", query.get("episode_no"));

        return new UrlMetadata(
                title,
                seriesName,
                seriesNumber,
                language,
                DownloadContentKind.WEBTOON,
                List.of(),
                writeJson(raw)
        );
    }

    private String webtoonsEpisodeSlug(List<String> segments) {
        if (segments.size() < 4) {
            return null;
        }
        String last = segments.getLast();
        if ("viewer".equalsIgnoreCase(last) && segments.size() >= 5) {
            return segments.get(segments.size() - 2);
        }
        if ("list".equalsIgnoreCase(last)) {
            return null;
        }
        return last;
    }

    private Float parseEpisodeNumber(String episodeSlug) {
        if (episodeSlug == null || episodeSlug.isBlank()) {
            return null;
        }
        Matcher matcher = EPISODE_NUMBER_PATTERN.matcher(episodeSlug);
        if (matcher.find()) {
            return parseFloat(matcher.group(1));
        }
        return null;
    }

    private boolean isWebtoonsUrl(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("webtoons.com") || normalized.endsWith(".webtoons.com");
    }

    private List<String> resolveAuthors(DownloadSearchCriteria criteria, UrlMetadata metadata) {
        if (criteria.getAuthor() != null && !criteria.getAuthor().isBlank()) {
            return List.of(criteria.getAuthor());
        }
        return metadata.authors() == null ? List.of() : metadata.authors();
    }

    private String extractFilename(String value) {
        try {
            String path = URI.create(value).getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception e) {
            return "download";
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            if (!key.isBlank()) {
                values.put(decode(key), decode(value));
            }
        }
        return values;
    }

    private String cleanLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if ("french".equals(normalized)) {
            return "fr";
        }
        if ("english".equals(normalized)) {
            return "en";
        }
        return normalized.matches("[a-z]{2,3}") ? normalized : null;
    }

    private String titleCaseSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        String[] words = slug.replace('_', '-').split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            String lower = word.toLowerCase(Locale.ROOT);
            if (!result.isEmpty() && isLowercaseTitleParticle(lower)) {
                result.append(lower);
            } else if (lower.matches("\\d+(?:\\.\\d+)?")) {
                result.append(lower);
            } else {
                result.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) {
                    result.append(lower.substring(1));
                }
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    private boolean isLowercaseTitleParticle(String word) {
        return "a".equals(word)
                || "an".equals(word)
                || "and".equals(word)
                || "de".equals(word)
                || "du".equals(word)
                || "des".equals(word)
                || "la".equals(word)
                || "le".equals(word)
                || "les".equals(word)
                || "of".equals(word)
                || "the".equals(word);
    }

    private String decode(String value) {
        if (value == null) {
            return null;
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private Float parseFloat(String value) {
        try {
            return value == null || value.isBlank() ? null : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Float firstNonNull(Float... values) {
        for (Float value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private DownloadContentKind firstNonNull(DownloadContentKind... values) {
        for (DownloadContentKind value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stripSeriesPrefix(String title, String seriesName) {
        if (title == null || title.isBlank() || seriesName == null || seriesName.isBlank()) {
            return title;
        }
        String prefix = seriesName + " - ";
        return title.regionMatches(true, 0, prefix, 0, prefix.length())
                ? title.substring(prefix.length()).trim()
                : title;
    }

    private DownloadContentKind galleryDlContentKind(JsonNode metadata) {
        String category = text(metadata, "category");
        if ("webtoons".equalsIgnoreCase(category)) {
            return DownloadContentKind.WEBTOON;
        }
        return null;
    }

    private String text(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            String value = node.path(key).asText(null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private GalleryDlProbeConfig readGalleryDlProbeConfig(DownloadSourceEntity source) {
        JsonNode node = firstGalleryDlSection(source);
        boolean enabled = node.path("metadataProbeEnabled").asBoolean(true);
        String binaryPath = firstNonBlank(
                node.path("binaryPath").asText(null),
                node.path("binary").asText(null),
                textFromJson(source.getConfigJson(), "galleryDlBinaryPath"),
                textFromJson(source.getCredentialsJson(), "galleryDlBinaryPath"),
                DEFAULT_GALLERY_DL_BINARY
        );
        long timeoutSeconds = clampLong(node.path("metadataProbeTimeoutSeconds").asLong(15), 1, 120);
        return new GalleryDlProbeConfig(enabled, binaryPath, timeoutSeconds, stringList(node.path("extraArgs")));
    }

    private JsonNode firstGalleryDlSection(DownloadSourceEntity source) {
        JsonNode config = sectionFromJson(source.getConfigJson(), "galleryDl");
        if (config.isObject()) {
            return config;
        }
        JsonNode credentials = sectionFromJson(source.getCredentialsJson(), "galleryDl");
        return credentials.isObject() ? credentials : objectMapper.createObjectNode();
    }

    private JsonNode sectionFromJson(String json, String section) {
        try {
            if (json == null || json.isBlank()) return objectMapper.createObjectNode();
            return objectMapper.readTree(json).path(section);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }

    private long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DownloadAcquisitionType inferAcquisitionType(DownloadSourceEntity source, String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("magnet:") || lower.endsWith(".torrent") || lower.contains(".torrent?")) {
            return DownloadAcquisitionType.TORRENT;
        }
        if (lower.endsWith(".nzb") || lower.contains(".nzb?")) {
            return DownloadAcquisitionType.NZB;
        }
        if (useGalleryDl(source)) {
            return DownloadAcquisitionType.CLI_GALLERY_DL;
        }
        return DownloadAcquisitionType.DIRECT_FILE;
    }

    private boolean useGalleryDl(DownloadSourceEntity source) {
        return boolFromJson(source.getConfigJson(), "useGalleryDl")
                || boolFromJson(source.getCredentialsJson(), "useGalleryDl")
                || nestedBoolFromJson(source.getConfigJson(), "galleryDl", "enabled")
                || nestedBoolFromJson(source.getCredentialsJson(), "galleryDl", "enabled")
                || "CLI_GALLERY_DL".equalsIgnoreCase(textFromJson(source.getConfigJson(), "acquisitionType"))
                || "CLI_GALLERY_DL".equalsIgnoreCase(textFromJson(source.getCredentialsJson(), "acquisitionType"));
    }

    private boolean useFlareSolverr(DownloadSourceEntity source) {
        return boolFromJson(source.getConfigJson(), "useFlareSolverr")
                || boolFromJson(source.getCredentialsJson(), "useFlareSolverr")
                || nestedBoolFromJson(source.getConfigJson(), "flareSolverr", "enabled")
                || nestedBoolFromJson(source.getCredentialsJson(), "flareSolverr", "enabled");
    }

    private boolean boolFromJson(String json, String key) {
        try {
            if (json == null || json.isBlank()) return false;
            JsonNode root = objectMapper.readTree(json);
            return root.path(key).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean nestedBoolFromJson(String json, String parent, String key) {
        try {
            if (json == null || json.isBlank()) return false;
            JsonNode root = objectMapper.readTree(json);
            return root.path(parent).path(key).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String textFromJson(String json, String key) {
        try {
            if (json == null || json.isBlank()) return null;
            JsonNode root = objectMapper.readTree(json);
            return root.path(key).asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record GalleryDlProbeConfig(boolean enabled, String binaryPath, long timeoutSeconds, List<String> extraArgs) {
    }

    private record UrlMetadata(String title,
                               String seriesName,
                               Float seriesNumber,
                               String language,
                               DownloadContentKind contentKind,
                               List<String> authors,
                               String rawJson) {

        private static UrlMetadata empty() {
            return new UrlMetadata(null, null, null, null, null, List.of(), null);
        }

        private UrlMetadata withFallback(UrlMetadata fallback) {
            return new UrlMetadata(
                    firstPresent(title, fallback.title),
                    firstPresent(seriesName, fallback.seriesName),
                    seriesNumber != null ? seriesNumber : fallback.seriesNumber,
                    firstPresent(language, fallback.language),
                    contentKind != null ? contentKind : fallback.contentKind,
                    authors != null && !authors.isEmpty() ? authors : fallback.authors,
                    firstPresent(rawJson, fallback.rawJson)
            );
        }

        private static String firstPresent(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
