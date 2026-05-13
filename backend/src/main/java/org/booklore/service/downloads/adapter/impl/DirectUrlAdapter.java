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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String DEFAULT_WEBTOONS_SEARCH_URL_TEMPLATE = "https://www.webtoons.com/en/search?keyword={query}";
    private static final int DEFAULT_WEBTOONS_SEARCH_TIMEOUT_SECONDS = 12;
    private static final int DEFAULT_WEBTOONS_MAX_SEARCH_RESULTS = 5;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadContentClassifier contentClassifier;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.DIRECT_URL;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        if (criteria.getDirectUrl() == null || criteria.getDirectUrl().isBlank()) {
            if (useGalleryDl(source) && isWebtoonsSearch(source, criteria)) {
                return searchWebtoons(source, criteria);
            }
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

    private boolean isWebtoonsSearch(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        if (criteria == null || criteria.effectiveQuery().isBlank()) {
            return false;
        }
        DownloadContentKind contentKind = criteria.getContentKind();
        return (contentKind != null && contentKind == DownloadContentKind.WEBTOON)
                || containsIgnoreCase(source.getName(), "webtoon")
                || containsIgnoreCase(source.getConfigJson(), "webtoon")
                || containsIgnoreCase(source.getCredentialsJson(), "webtoon");
    }

    private List<NormalizedDownloadResult> searchWebtoons(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        WebtoonsSearchConfig config = readWebtoonsSearchConfig(source);
        if (!config.enabled()) {
            return List.of();
        }
        try {
            String searchUrl = config.searchUrlTemplate()
                    .replace("{query}", URLEncoder.encode(criteria.effectiveQuery(), StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(searchUrl))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                return List.of();
            }
            List<WebtoonsSeriesCandidate> candidates = parseWebtoonsSearch(response.body(), criteria.effectiveQuery());
            List<NormalizedDownloadResult> results = new ArrayList<>();
            for (WebtoonsSeriesCandidate candidate : candidates.stream().limit(config.maxResults()).toList()) {
                String resolvedUrl = criteria.getSeriesNumber() == null
                        ? candidate.url()
                        : resolveWebtoonsEpisodeUrl(candidate.url(), criteria.getSeriesNumber(), config).orElse(candidate.url());
                UrlMetadata metadata = inferUrlMetadata(resolvedUrl, DownloadAcquisitionType.CLI_GALLERY_DL)
                        .orElse(new UrlMetadata(candidate.title(), candidate.title(), criteria.getSeriesNumber(), candidate.language(), DownloadContentKind.WEBTOON, candidate.author() == null ? List.of() : List.of(candidate.author()), null));
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("provider", "webtoons-search");
                raw.put("query", criteria.effectiveQuery());
                raw.put("seriesUrl", candidate.url());
                raw.put("resolvedUrl", resolvedUrl);
                raw.put("titleNo", candidate.titleNo());
                raw.put("score", candidate.score());
                raw.put("section", candidate.section());
                raw.put("metadata", metadata.rawJson());

                results.add(NormalizedDownloadResult.builder()
                        .sourceResultId(resolvedUrl)
                        .title(firstNonBlank(metadata.title(), candidate.title()))
                        .authors(resolveAuthors(criteria, metadata.authors() == null || metadata.authors().isEmpty()
                                ? new UrlMetadata(null, null, null, null, null, candidate.author() == null ? List.of() : List.of(candidate.author()), null)
                                : metadata))
                        .seriesName(firstNonBlank(criteria.getSeriesName(), metadata.seriesName(), candidate.title()))
                        .seriesNumber(criteria.getSeriesNumber() != null ? criteria.getSeriesNumber() : metadata.seriesNumber())
                        .language(firstNonBlank(metadata.language(), candidate.language()))
                        .contentKind(DownloadContentKind.WEBTOON)
                        .format(DownloadFormat.CBZ)
                        .downloadUrl(resolvedUrl)
                        .detailsUrl(candidate.url())
                        .requiresFlareSolverr(useFlareSolverr(source))
                        .acquisitionType(DownloadAcquisitionType.CLI_GALLERY_DL)
                        .rawJson(writeJson(raw))
                        .build());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<WebtoonsSeriesCandidate> parseWebtoonsSearch(String html, String query) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Document document = Jsoup.parse(html);
        List<WebtoonsSeriesCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element link : document.select("a[href*=title_no][href*=/list]")) {
            String url = link.absUrl("href");
            if (url.isBlank()) {
                url = link.attr("href");
            }
            if (url.isBlank() || !seen.add(url)) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(url);
            } catch (Exception ignored) {
                continue;
            }
            if (!isWebtoonsUrl(uri)) {
                continue;
            }
            UrlMetadata metadata = parseWebtoonsMetadata(uri, url);
            String title = firstNonBlank(link.selectFirst(".title") == null ? null : link.selectFirst(".title").text(), metadata.seriesName(), link.attr("title"));
            String author = textOrNull(link.selectFirst(".author"));
            String section = firstNonBlank(link.attr("data-webtoon-type"), metadata.rawJson());
            String titleNo = parseQuery(uri.getRawQuery()).get("title_no");
            int score = titleMatchScore(query, title);
            candidates.add(new WebtoonsSeriesCandidate(url, title, author, metadata.language(), titleNo, section, score));
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(WebtoonsSeriesCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.title() == null ? "" : candidate.title()))
                .toList();
    }

    private Optional<String> resolveWebtoonsEpisodeUrl(String seriesUrl, Float requestedNumber, WebtoonsSearchConfig config) {
        if (seriesUrl == null || seriesUrl.isBlank() || requestedNumber == null) {
            return Optional.empty();
        }
        for (int page = 1; page <= config.maxEpisodePages(); page++) {
            String url = withQueryParam(seriesUrl, "page", String.valueOf(page));
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("User-Agent", "BookLore-Downloads")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() > 299) {
                    continue;
                }
                Optional<String> match = parseWebtoonsEpisodeUrl(response.body(), requestedNumber);
                if (match.isPresent()) {
                    return match;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<String> parseWebtoonsEpisodeUrl(String html, Float requestedNumber) {
        Document document = Jsoup.parse(html);
        for (Element link : document.select("a[href*=episode_no][href*=/viewer]")) {
            String url = link.absUrl("href");
            if (url.isBlank()) {
                url = link.attr("href");
            }
            if (url.isBlank()) {
                continue;
            }
            UrlMetadata metadata;
            try {
                metadata = parseWebtoonsMetadata(URI.create(url), url);
            } catch (Exception ignored) {
                continue;
            }
            Float number = firstNonNull(metadata.seriesNumber(), parseEpisodeNumber(link.text()));
            if (number != null && Math.abs(number - requestedNumber) < 0.001f) {
                return Optional.of(url);
            }
        }
        return Optional.empty();
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

    private WebtoonsSearchConfig readWebtoonsSearchConfig(DownloadSourceEntity source) {
        JsonNode node = firstGalleryDlSection(source);
        JsonNode webtoons = node.path("webtoons");
        boolean enabled = webtoons.path("globalSearchEnabled").asBoolean(node.path("globalSearchEnabled").asBoolean(true));
        String searchUrlTemplate = firstNonBlank(
                webtoons.path("searchUrlTemplate").asText(null),
                webtoons.path("searchUrl").asText(null),
                node.path("webtoonsSearchUrlTemplate").asText(null),
                node.path("webtoonSearchUrlTemplate").asText(null),
                DEFAULT_WEBTOONS_SEARCH_URL_TEMPLATE
        );
        int timeoutSeconds = (int) clampLong(
                webtoons.path("timeoutSeconds").asLong(node.path("webtoonsSearchTimeoutSeconds").asLong(DEFAULT_WEBTOONS_SEARCH_TIMEOUT_SECONDS)),
                1,
                60
        );
        int maxResults = (int) clampLong(
                webtoons.path("maxResults").asLong(node.path("webtoonsMaxSearchResults").asLong(DEFAULT_WEBTOONS_MAX_SEARCH_RESULTS)),
                1,
                20
        );
        int maxEpisodePages = (int) clampLong(
                webtoons.path("maxEpisodePages").asLong(node.path("webtoonsMaxEpisodePages").asLong(3)),
                1,
                20
        );
        return new WebtoonsSearchConfig(enabled, searchUrlTemplate, timeoutSeconds, maxResults, maxEpisodePages);
    }

    private String withQueryParam(String value, String key, String parameterValue) {
        String separator = value.contains("?") ? "&" : "?";
        return value + separator + URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(parameterValue, StandardCharsets.UTF_8);
    }

    private int titleMatchScore(String query, String title) {
        if (query == null || query.isBlank() || title == null || title.isBlank()) {
            return 0;
        }
        List<String> queryTokens = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 1 && !token.matches("\\d+"))
                .toList();
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        if (normalizedTitle.equals(query.toLowerCase(Locale.ROOT))) {
            return 1000;
        }
        int score = 0;
        for (String token : queryTokens) {
            if (normalizedTitle.contains(token)) {
                score += 100;
            }
        }
        return score;
    }

    private String textOrNull(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.text();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
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

    private record WebtoonsSearchConfig(boolean enabled,
                                        String searchUrlTemplate,
                                        int timeoutSeconds,
                                        int maxResults,
                                        int maxEpisodePages) {
    }

    private record WebtoonsSeriesCandidate(String url,
                                           String title,
                                           String author,
                                           String language,
                                           String titleNo,
                                           String section,
                                           int score) {
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
