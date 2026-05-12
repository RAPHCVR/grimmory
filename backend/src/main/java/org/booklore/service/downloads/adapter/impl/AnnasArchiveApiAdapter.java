package org.booklore.service.downloads.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AnnasArchiveApiAdapter implements DownloadSourceAdapter {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final Pattern SIZE_PATTERN = Pattern.compile("(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*(b|kb|kib|mb|mib|gb|gib)?\\s*$");

    private static final List<String> DEFAULT_RESULT_PATHS = List.of("results", "data", "items", "books");
    private static final List<String> DEFAULT_DOWNLOAD_URL_FIELDS = List.of(
            "downloadUrl", "download_url", "directUrl", "direct_url", "url", "href", "link", "mirror", "download.link"
    );
    private static final List<String> DEFAULT_TITLE_FIELDS = List.of("title", "name", "book.title", "metadata.title");
    private static final List<String> DEFAULT_AUTHOR_FIELDS = List.of("authors", "author", "creator", "book.author", "metadata.authors");
    private static final List<String> DEFAULT_DETAILS_FIELDS = List.of("detailsUrl", "details_url", "sourceUrl", "source_url", "pageUrl", "page_url", "page", "details");
    private static final List<String> DEFAULT_ID_FIELDS = List.of("id", "md5", "hash", "externalId", "external_id", "guid");
    private static final List<String> DEFAULT_FORMAT_FIELDS = List.of("format", "extension", "ext", "file.extension");
    private static final List<String> DEFAULT_LANGUAGE_FIELDS = List.of("language", "lang", "metadata.language");
    private static final List<String> DEFAULT_ISBN_FIELDS = List.of("isbn", "isbn13", "isbn10", "metadata.isbn");
    private static final List<String> DEFAULT_YEAR_FIELDS = List.of("year", "publishedYear", "published_year", "publication_year", "metadata.year");
    private static final List<String> DEFAULT_SIZE_FIELDS = List.of("sizeBytes", "size_bytes", "filesize", "fileSize", "size");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.ANNAS_ARCHIVE_API;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        AnnasArchiveApiConfig config = readConfig(source);
        String term = criteria.effectiveQuery();
        if (term.isBlank()) {
            return List.of();
        }

        URI uri = buildSearchUri(config, term, preferredFormat(criteria, config.defaultFormat()), Math.min(Math.max(1, criteria.getMaxResults()), config.maxResults()));
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET();
            addAuthenticationHeaders(request, config);

            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("Anna API search failed with HTTP status " + response.statusCode());
            }
            return parseResults(response.body(), criteria, config);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("Anna API search interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("Anna API search failed: " + e.getMessage(), e);
        }
    }

    private URI buildSearchUri(AnnasArchiveApiConfig config, String term, DownloadFormat format, int maxResults) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.baseUrl())
                .queryParam(config.queryParam(), term);
        if (config.limitParam() != null) {
            builder.queryParam(config.limitParam(), maxResults);
        }
        if (config.formatParam() != null) {
            builder.queryParam(config.formatParam(), format.extension());
        }
        if (config.apiKey() != null && config.apiKeyQueryParam() != null) {
            builder.queryParam(config.apiKeyQueryParam(), config.apiKey());
        }
        return builder.build().encode().toUri();
    }

    private void addAuthenticationHeaders(HttpRequest.Builder request, AnnasArchiveApiConfig config) {
        if (config.apiKey() == null) {
            return;
        }
        if (config.authorizationScheme() != null) {
            request.header("Authorization", config.authorizationScheme() + " " + config.apiKey());
            return;
        }
        if (config.apiKeyHeader() != null) {
            request.header(config.apiKeyHeader(), config.apiKey());
        }
    }

    private List<NormalizedDownloadResult> parseResults(String body, DownloadSearchCriteria criteria, AnnasArchiveApiConfig config) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode resultsNode = resultsNode(root, config.resultsPath());
        if (!resultsNode.isArray()) {
            throw new DownloadSourceException("Anna API response does not contain a result array");
        }

        List<NormalizedDownloadResult> results = new ArrayList<>();
        int limit = Math.max(1, criteria.getMaxResults());
        for (JsonNode item : resultsNode) {
            if (results.size() >= limit) {
                break;
            }
            String downloadUrl = firstText(item, config.downloadUrlFields());
            String id = firstText(item, config.idFields());
            if ((downloadUrl == null || downloadUrl.isBlank()) && config.downloadUrlTemplate() != null && id != null) {
                downloadUrl = config.downloadUrlTemplate()
                        .replace("{id}", id)
                        .replace("{md5}", id)
                        .replace("{hash}", id);
            }
            if ((downloadUrl == null || downloadUrl.isBlank()) && config.acquisitionType() != DownloadAcquisitionType.EXTERNAL_STACKS) {
                continue;
            }
            if (config.acquisitionType() == DownloadAcquisitionType.EXTERNAL_STACKS && firstNonBlank(id, downloadUrl) == null) {
                continue;
            }

            String title = firstNonBlank(firstText(item, config.titleFields()), "Untitled");
            DownloadFormat format = firstFormat(item, config.formatFields(), downloadUrl, config.defaultFormat());
            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(firstNonBlank(id, downloadUrl, title))
                    .title(title)
                    .authors(authors(item, config.authorFields()))
                    .publishedYear(firstInt(item, config.yearFields()))
                    .isbn(firstText(item, config.isbnFields()))
                    .language(firstText(item, config.languageFields()))
                    .format(format)
                    .contentKind(Optional.ofNullable(criteria.getContentKind()).orElse(DownloadContentKind.BOOK))
                    .acquisitionType(config.acquisitionType())
                    .sizeBytes(firstSize(item, config.sizeFields()))
                    .downloadUrl(downloadUrl)
                    .detailsUrl(firstText(item, config.detailsUrlFields()))
                    .requiresFlareSolverr(config.requiresFlareSolverr())
                    .rawJson(objectMapper.writeValueAsString(item))
                    .build());
        }
        return results;
    }

    private JsonNode resultsNode(JsonNode root, String configuredPath) {
        if (root.isArray()) {
            return root;
        }
        if (configuredPath != null) {
            return path(root, configuredPath);
        }
        for (String path : DEFAULT_RESULT_PATHS) {
            JsonNode node = path(root, path);
            if (node.isArray()) {
                return node;
            }
        }
        return root;
    }

    private AnnasArchiveApiConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "annasArchiveApi");
        String baseUrl = firstNonBlank(
                node.path("baseUrl").asText(null),
                node.path("searchUrl").asText(null),
                configReader.firstText(source, "baseUrl", null),
                configReader.firstText(source, "searchUrl", null)
        );
        if (baseUrl == null) {
            throw new DownloadSourceException("Anna API source requires baseUrl in credentials_json or config_json.annasArchiveApi");
        }

        DownloadFormat defaultFormat = DownloadFormat.fromText(firstNonBlank(node.path("defaultFormat").asText(null), "epub"));
        if (defaultFormat == DownloadFormat.UNKNOWN) {
            defaultFormat = DownloadFormat.EPUB;
        }

        String apiKey = firstNonBlank(node.path("apiKey").asText(null), configReader.firstText(source, "apiKey", null));
        String apiKeyHeader = blankToNull(node.path("apiKeyHeader").asText("X-API-Key"));
        String apiKeyQueryParam = blankToNull(node.path("apiKeyQueryParam").asText(null));
        String authorizationScheme = blankToNull(node.path("authorizationScheme").asText(null));
        if (apiKeyHeader == null && apiKeyQueryParam == null && authorizationScheme == null) {
            apiKeyHeader = "X-API-Key";
        }

        return new AnnasArchiveApiConfig(
                baseUrl,
                blankToNull(node.path("queryParam").asText("q")),
                blankToNull(node.path("formatParam").asText("ext")),
                defaultFormat,
                Math.max(3, node.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS)),
                Math.max(1, node.path("maxResults").asInt(50)),
                apiKey,
                apiKeyHeader,
                apiKeyQueryParam,
                authorizationScheme,
                blankToNull(node.path("limitParam").asText("limit")),
                blankToNull(node.path("resultsPath").asText(null)),
                node.path("requiresFlareSolverr").asBoolean(true),
                acquisitionType(node),
                blankToNull(node.path("downloadUrlTemplate").asText(null)),
                stringList(node.path("downloadUrlFields"), DEFAULT_DOWNLOAD_URL_FIELDS),
                stringList(node.path("titleFields"), DEFAULT_TITLE_FIELDS),
                stringList(node.path("authorFields"), DEFAULT_AUTHOR_FIELDS),
                stringList(node.path("detailsUrlFields"), DEFAULT_DETAILS_FIELDS),
                stringList(node.path("idFields"), DEFAULT_ID_FIELDS),
                stringList(node.path("formatFields"), DEFAULT_FORMAT_FIELDS),
                stringList(node.path("languageFields"), DEFAULT_LANGUAGE_FIELDS),
                stringList(node.path("isbnFields"), DEFAULT_ISBN_FIELDS),
                stringList(node.path("yearFields"), DEFAULT_YEAR_FIELDS),
                stringList(node.path("sizeFields"), DEFAULT_SIZE_FIELDS)
        );
    }

    private DownloadAcquisitionType acquisitionType(JsonNode node) {
        if (node.path("useStacks").asBoolean(false)) {
            return DownloadAcquisitionType.EXTERNAL_STACKS;
        }
        String value = blankToNull(node.path("acquisitionType").asText(null));
        if (value == null) {
            return DownloadAcquisitionType.DIRECT_FILE;
        }
        try {
            return DownloadAcquisitionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DownloadAcquisitionType.DIRECT_FILE;
        }
    }

    private DownloadFormat preferredFormat(DownloadSearchCriteria criteria, DownloadFormat defaultFormat) {
        if (criteria.getPreferredFormats() != null) {
            for (DownloadFormat preferred : criteria.getPreferredFormats()) {
                if (preferred != null && preferred != DownloadFormat.UNKNOWN) {
                    return preferred;
                }
            }
        }
        return defaultFormat;
    }

    private DownloadFormat firstFormat(JsonNode item, List<String> fields, String downloadUrl, DownloadFormat defaultFormat) {
        DownloadFormat fromField = DownloadFormat.fromText(firstText(item, fields));
        if (fromField != DownloadFormat.UNKNOWN) {
            return fromField;
        }
        return DownloadFormat.fromFileName(downloadUrl).orElse(defaultFormat);
    }

    private List<String> authors(JsonNode item, List<String> fields) {
        for (String field : fields) {
            JsonNode node = path(item, field);
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isArray()) {
                List<String> authors = new ArrayList<>();
                for (JsonNode author : node) {
                    String value = author.isObject()
                            ? firstNonBlank(author.path("name").asText(null), author.path("author").asText(null), author.path("title").asText(null))
                            : author.asText(null);
                    if (value != null && !value.isBlank()) {
                        authors.add(value.trim());
                    }
                }
                if (!authors.isEmpty()) {
                    return authors.stream().distinct().toList();
                }
            }
            String value = node.asText(null);
            if (value != null && !value.isBlank()) {
                return splitPeople(value);
            }
        }
        return List.of();
    }

    private String firstText(JsonNode node, List<String> fields) {
        for (String field : fields) {
            JsonNode value = path(node, field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private Integer firstInt(JsonNode item, List<String> fields) {
        String value = firstText(item, fields);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long firstSize(JsonNode item, List<String> fields) {
        for (String field : fields) {
            JsonNode node = path(item, field);
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isNumber()) {
                long value = node.asLong(0L);
                return value > 0 ? value : null;
            }
            Long parsed = parseSize(node.asText(null));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Long parseSize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = SIZE_PATTERN.matcher(value.replace(",", "."));
        if (!matcher.matches()) {
            return null;
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = Optional.ofNullable(matcher.group(2)).orElse("b").toLowerCase(Locale.ROOT);
        long multiplier = switch (unit) {
            case "kb", "kib" -> 1024L;
            case "mb", "mib" -> 1024L * 1024L;
            case "gb", "gib" -> 1024L * 1024L * 1024L;
            default -> 1L;
        };
        long bytes = (long) (amount * multiplier);
        return bytes > 0 ? bytes : null;
    }

    private JsonNode path(JsonNode node, String dottedPath) {
        JsonNode current = node;
        for (String segment : dottedPath.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return current;
            }
        }
        return current;
    }

    private List<String> stringList(JsonNode node, List<String> defaultValue) {
        if (!node.isArray()) {
            return defaultValue;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? defaultValue : List.copyOf(values);
    }

    private List<String> splitPeople(String value) {
        return Arrays.stream(value.split("[,;|]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record AnnasArchiveApiConfig(String baseUrl,
                                         String queryParam,
                                         String formatParam,
                                         DownloadFormat defaultFormat,
                                         int timeoutSeconds,
                                         int maxResults,
                                         String apiKey,
                                         String apiKeyHeader,
                                         String apiKeyQueryParam,
                                         String authorizationScheme,
                                         String limitParam,
                                         String resultsPath,
                                         boolean requiresFlareSolverr,
                                         DownloadAcquisitionType acquisitionType,
                                         String downloadUrlTemplate,
                                         List<String> downloadUrlFields,
                                         List<String> titleFields,
                                         List<String> authorFields,
                                         List<String> detailsUrlFields,
                                         List<String> idFields,
                                         List<String> formatFields,
                                         List<String> languageFields,
                                         List<String> isbnFields,
                                         List<String> yearFields,
                                         List<String> sizeFields) {
    }
}
