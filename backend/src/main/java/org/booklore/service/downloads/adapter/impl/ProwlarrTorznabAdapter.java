package org.booklore.service.downloads.adapter.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.DownloadContentClassifier;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProwlarrTorznabAdapter implements DownloadSourceAdapter {

    private static final int DEFAULT_TIMEOUT_SECONDS = 20;
    private static final String DEFAULT_INDEXER = "all";
    private static final Pattern UNSUPPORTED_SEQUENTIAL_MEDIA_MARKER = Pattern.compile(
            "(?i)(?:\\bmp4\\b|\\bmkv\\b|\\bavi\\b|\\bmov\\b|\\bwmv\\b|\\bflac\\b|\\bmp3\\b|\\baac\\b|\\bopus\\b|\\b480p\\b|\\b720p\\b|\\b1080p\\b|\\b2160p\\b|\\bfullhd\\b|\\bbdrip\\b|\\bwebrip\\b|\\bhdtv\\b|\\bbluray\\b|\\bblu ray\\b|\\bx264\\b|\\bx265\\b|\\bhevc\\b|\\bh\\s?264\\b|\\bh\\s?265\\b|\\b10bit\\b|\\bdual audio\\b|\\bsubbed\\b|\\bsoftsubs?\\b|\\bvostfr\\b|\\bsub ita\\b|\\bsub esp\\b|\\bsoundtrack\\b|\\bost\\b|\\bs\\d{1,2}\\s?e\\d{1,3}\\b|\\btv anime\\b|\\bmovies other\\b|\\bfitgirl\\b|\\bdodi\\b|\\belamigos\\b|\\bsteamrip\\b|\\bskidrow\\b|\\breloaded\\b|\\bplaza\\b|\\brazor1911\\b|\\bcodex\\b|\\bgame repack\\b)"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadContentClassifier contentClassifier;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.PROWLARR_TORZNAB;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        TorznabConfig config = readConfig(source);
        String term = criteria.effectiveQuery();
        if (term.isBlank()) {
            return List.of();
        }

        return config.nativeProwlarrApi()
                ? searchProwlarrNative(config, source.getName(), term, criteria)
                : searchTorznab(config, source.getName(), term, criteria);
    }

    private List<NormalizedDownloadResult> searchTorznab(TorznabConfig config, String sourceName, String term, DownloadSearchCriteria criteria) {
        URI uri = UriComponentsBuilder.fromUriString(config.baseUrl())
                .pathSegment("api", "v2.0", "indexers", config.indexer(), "results", "torznab", "api")
                .queryParam("apikey", config.apiKey())
                .queryParam("t", config.function())
                .queryParam("q", term)
                .queryParam("limit", Math.max(1, criteria.getMaxResults()))
                .queryParamIfPresent("cat", Optional.ofNullable(config.categories()).filter(s -> !s.isBlank()))
                .build()
                .toUri();

        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Accept", "application/rss+xml, application/xml, text/xml")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("Torznab search failed with HTTP status " + response.statusCode());
            }
            return parseTorznabResults(response.body(), sourceName, criteria);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Torznab search interrupted", e);
        } catch (Exception e) {
            if (e instanceof DownloadSourceException sourceException) {
                throw sourceException;
            }
            throw new DownloadSourceException("Torznab search failed: " + e.getMessage(), e);
        }
    }

    private List<NormalizedDownloadResult> searchProwlarrNative(TorznabConfig config, String sourceName, String term, DownloadSearchCriteria criteria) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.baseUrl())
                .pathSegment("api", "v1", "search")
                .queryParam("apikey", config.apiKey())
                .queryParam("query", term)
                .queryParam("type", config.function())
                .queryParam("limit", Math.max(1, criteria.getMaxResults()))
                .queryParamIfPresent("categories", Optional.ofNullable(config.categories()).filter(s -> !s.isBlank()));

        for (String indexerId : splitCsv(config.indexerIds())) {
            builder.queryParam("indexerIds", indexerId);
        }

        URI uri = builder.build().toUri();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("Prowlarr search failed with HTTP status " + response.statusCode());
            }
            return parseProwlarrResults(response.body(), sourceName, criteria);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Prowlarr search interrupted", e);
        } catch (Exception e) {
            if (e instanceof DownloadSourceException sourceException) {
                throw sourceException;
            }
            throw new DownloadSourceException("Prowlarr search failed: " + e.getMessage(), e);
        }
    }

    private List<NormalizedDownloadResult> parseTorznabResults(String xml, String sourceName, DownloadSearchCriteria criteria) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList items = document.getElementsByTagName("item");
        List<NormalizedDownloadResult> results = new ArrayList<>(items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            Node node = items.item(i);
            if (!(node instanceof Element item)) continue;

            Map<String, String> attrs = torznabAttributes(item);
            String title = text(item, "title");
            String link = firstNonBlank(text(item, "link"), attrs.get("downloadurl"), attrs.get("downloadUrl"));
            String details = firstNonBlank(text(item, "comments"), text(item, "guid"), attrs.get("details"));
            String guid = firstNonBlank(text(item, "guid"), link, title);
            Long size = firstLong(attrs.get("size"), text(item, "size"), text(item, "length"));
            DownloadFormat format = inferFormat(title, attrs);
            DownloadAcquisitionType acquisitionType = inferAcquisitionType(link);
            String rawJson = objectMapper.writeValueAsString(attrs);
            if (shouldSkipUnsupportedSequentialPayload(criteria, title, details, link, rawJson, acquisitionType, format)) {
                continue;
            }
            DownloadContentKind contentKind = contentClassifier.resolve(
                    criteria.getContentKind(),
                    contentClassifier.infer(sourceType(), sourceName, title, firstNonBlank(attrs.get("series"), attrs.get("seriesName")), details, link, format, acquisitionType, rawJson),
                    DownloadContentKind.BOOK
            );

            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(guid)
                    .title(title == null || title.isBlank() ? "Untitled" : title)
                    .authors(splitPeople(firstNonBlank(attrs.get("author"), attrs.get("authors"))))
                    .seriesName(firstNonBlank(attrs.get("series"), attrs.get("seriesName")))
                    .seriesNumber(firstFloat(firstNonBlank(attrs.get("seriesnumber"), attrs.get("seriesNumber"), attrs.get("volume"))))
                    .publishedYear(firstInt(firstNonBlank(attrs.get("year"), attrs.get("publishyear"))))
                    .isbn(firstNonBlank(attrs.get("isbn"), attrs.get("isbn13"), attrs.get("isbn10")))
                    .language(firstNonBlank(attrs.get("language"), attrs.get("lang")))
                    .format(format)
                    .contentKind(contentKind)
                    .acquisitionType(acquisitionType)
                    .sizeBytes(size)
                    .downloadUrl(link)
                    .detailsUrl(details)
                    .rawJson(rawJson)
                    .build());
        }
        return results;
    }

    private List<NormalizedDownloadResult> parseProwlarrResults(String json, String sourceName, DownloadSearchCriteria criteria) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new DownloadSourceException("Prowlarr search response is not an array");
        }

        List<NormalizedDownloadResult> results = new ArrayList<>();
        for (JsonNode item : root) {
            String title = item.path("title").asText(null);
            String downloadUrl = firstNonBlank(item.path("downloadUrl").asText(null), item.path("magnetUrl").asText(null));
            String detailsUrl = firstNonBlank(item.path("infoUrl").asText(null), item.path("commentUrl").asText(null));
            String guid = firstNonBlank(item.path("guid").asText(null), downloadUrl, title);
            String sourceResultId = firstNonBlank(item.path("infoHash").asText(null), guid);
            String protocol = item.path("protocol").asText(null);
            String rawJson = objectMapper.writeValueAsString(item);
            DownloadFormat format = inferProwlarrFormat(title, rawJson);
            DownloadAcquisitionType acquisitionType = inferProwlarrAcquisitionType(downloadUrl, protocol);
            if (shouldSkipUnsupportedSequentialPayload(criteria, title, detailsUrl, downloadUrl, rawJson, acquisitionType, format)) {
                continue;
            }
            DownloadContentKind contentKind = contentClassifier.resolve(
                    criteria.getContentKind(),
                    contentClassifier.infer(sourceType(), sourceName, title, null, detailsUrl, downloadUrl, format, acquisitionType, rawJson),
                    DownloadContentKind.BOOK
            );

            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(sourceResultId)
                    .title(title == null || title.isBlank() ? "Untitled" : title)
                    .authors(List.of())
                    .publishedYear(yearFromIsoDate(item.path("publishDate").asText(null)))
                    .format(format)
                    .contentKind(contentKind)
                    .acquisitionType(acquisitionType)
                    .sizeBytes(positiveLong(item.path("size").asLong(0L)))
                    .downloadUrl(downloadUrl)
                    .detailsUrl(detailsUrl)
                    .rawJson(rawJson)
                    .build());
        }
        return results;
    }

    private TorznabConfig readConfig(DownloadSourceEntity source) {
        try {
            JsonNode root = objectMapper.readTree(source.getCredentialsJson() == null ? "{}" : source.getCredentialsJson());
            String baseUrl = root.path("baseUrl").asText(null);
            String apiKey = root.path("apiKey").asText(null);
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new DownloadSourceException("Prowlarr/Torznab source requires credentials_json.baseUrl");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new DownloadSourceException("Prowlarr/Torznab source requires credentials_json.apiKey");
            }
            String indexer = root.path("indexer").asText(DEFAULT_INDEXER);
            String indexerIds = root.path("indexerIds").asText(null);
            String function = root.path("function").asText("search");
            String categories = root.path("categories").asText(null);
            String apiMode = root.path("apiMode").asText("PROWLARR");
            int timeout = Math.max(3, root.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS));
            return new TorznabConfig(trimTrailingSlash(baseUrl), apiKey, indexer, indexerIds, function, categories, timeout, apiMode);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("Invalid Prowlarr/Torznab credentials_json", e);
        }
    }

    private Map<String, String> torznabAttributes(Element item) {
        Map<String, String> values = new LinkedHashMap<>();
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) continue;
            String nodeName = element.getNodeName();
            if (!"attr".equals(nodeName) && !nodeName.endsWith(":attr")) continue;
            String name = element.getAttribute("name");
            String value = element.getAttribute("value");
            if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                values.put(name.trim(), value.trim());
            }
        }
        return values;
    }

    private String text(Element item, String tagName) {
        NodeList list = item.getElementsByTagName(tagName);
        if (list.getLength() == 0 || list.item(0) == null) {
            return null;
        }
        return list.item(0).getTextContent();
    }

    private DownloadFormat inferFormat(String title, Map<String, String> attrs) {
        for (String key : List.of("format", "extension", "filetype")) {
            DownloadFormat format = DownloadFormat.fromText(attrs.get(key));
            if (format != DownloadFormat.UNKNOWN) {
                return format;
            }
        }
        DownloadFormat titleFormat = DownloadFormat.fromFileName(title).orElse(DownloadFormat.UNKNOWN);
        if (titleFormat != DownloadFormat.UNKNOWN) {
            return titleFormat;
        }
        return DownloadFormat.fromText(title + " " + String.join(" ", attrs.values()));
    }

    private DownloadFormat inferProwlarrFormat(String title, String rawJson) {
        return DownloadFormat.fromFileName(title)
                .orElseGet(() -> {
                    DownloadFormat titleFormat = DownloadFormat.fromText(title);
                    return titleFormat != DownloadFormat.UNKNOWN ? titleFormat : DownloadFormat.fromText(rawJson);
                });
    }

    private DownloadAcquisitionType inferAcquisitionType(String link) {
        if (link == null) return DownloadAcquisitionType.UNKNOWN;
        String lower = link.toLowerCase(Locale.ROOT);
        if (lower.contains(".nzb") || lower.contains("nzb")) return DownloadAcquisitionType.NZB;
        if (lower.startsWith("magnet:") || lower.contains(".torrent") || lower.contains("torrent")) return DownloadAcquisitionType.TORRENT;
        return DownloadAcquisitionType.DIRECT_FILE;
    }

    private DownloadAcquisitionType inferProwlarrAcquisitionType(String link, String protocol) {
        if (protocol != null) {
            String normalized = protocol.trim().toLowerCase(Locale.ROOT);
            if ("torrent".equals(normalized) || "2".equals(normalized)) {
                return DownloadAcquisitionType.TORRENT;
            }
            if ("usenet".equals(normalized) || "1".equals(normalized)) {
                return DownloadAcquisitionType.NZB;
            }
        }
        return inferAcquisitionType(link);
    }

    private boolean shouldSkipUnsupportedSequentialPayload(DownloadSearchCriteria criteria,
                                                           String title,
                                                           String detailsUrl,
                                                           String downloadUrl,
                                                           String rawJson,
                                                           DownloadAcquisitionType acquisitionType,
                                                           DownloadFormat format) {
        DownloadContentKind requested = criteria.getContentKind() == null ? DownloadContentKind.AUTO : criteria.getContentKind();
        if (!requested.isSequentialArt()) {
            return false;
        }
        boolean externalPayload = acquisitionType == DownloadAcquisitionType.TORRENT || acquisitionType == DownloadAcquisitionType.NZB;
        boolean unknownFormat = format == null || format == DownloadFormat.UNKNOWN;
        if (!externalPayload && !unknownFormat) {
            return false;
        }
        String evidence = String.join(" ",
                safe(title),
                safe(detailsUrl),
                safe(downloadUrl),
                safe(rawJson)
        );
        return UNSUPPORTED_SEQUENTIAL_MEDIA_MARKER.matcher(evidence).find();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private Long firstLong(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                log.debug("Ignoring non numeric size value {}", value);
            }
        }
        return null;
    }

    private Integer firstInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Float firstFloat(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> splitPeople(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,;|]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private Long positiveLong(long value) {
        return value > 0 ? value : null;
    }

    private Integer yearFromIsoDate(String value) {
        if (value == null || value.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(value.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record TorznabConfig(String baseUrl,
                                 String apiKey,
                                 String indexer,
                                 String indexerIds,
                                 String function,
                                 String categories,
                                 int timeoutSeconds,
                                 String apiMode) {
        boolean nativeProwlarrApi() {
            return !"TORZNAB".equalsIgnoreCase(apiMode);
        }
    }
}
