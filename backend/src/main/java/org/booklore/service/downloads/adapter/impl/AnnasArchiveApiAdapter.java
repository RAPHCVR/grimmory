package org.booklore.service.downloads.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.DownloadSourceConfigReader;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;
import org.booklore.service.downloads.client.FlareSolverrClient;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AnnasArchiveApiAdapter implements DownloadSourceAdapter {

    private static final String DEFAULT_SEARCH_URL = "https://annas-archive.li/search";
    private static final String DEFAULT_SEARCH_PATH = "/search";
    private static final String DEFAULT_RESULT_LINK_SELECTOR = "a.js-vim-focus[href*=/md5/], a.font-semibold[href*=/md5/]";
    private static final String GENERIC_MD5_LINK_SELECTOR = "a[href*=/md5/]";
    private static final List<String> DEFAULT_FALLBACK_BASE_URLS = List.of(
            "https://annas-archive.gl",
            "https://annas-archive.gd",
            "https://annas-archive.pk"
    );
    private static final Pattern MD5_PATTERN = Pattern.compile("(?i)/md5/([a-f0-9]{32})");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(1[5-9]\\d{2}|20\\d{2})\\b");
    private static final Pattern SIZE_PATTERN = Pattern.compile("(?i)\\b(\\d+(?:[\\.,]\\d+)?)\\s*(kb|kib|mb|mib|gb|gib)\\b");

    private final FlareSolverrClient flareSolverrClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.ANNAS_ARCHIVE_API;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        AnnasArchiveHtmlConfig config = readConfig(source);
        String term = criteria.effectiveQuery();
        if (term.isBlank()) {
            return List.of();
        }

        DownloadFormat preferredFormat = preferredFormat(criteria, config.defaultFormat());
        Exception lastFailure = null;
        boolean atLeastOneRendered = false;
        for (String searchUrl : config.searchUrls()) {
            URI searchUri = buildSearchUri(config, searchUrl, term, preferredFormat);
            String siteBaseUrl = siteBaseUrl(searchUri.toString());
            try {
                String html = flareSolverrClient.fetchHtml(source, searchUri.toString());
                atLeastOneRendered = true;
                List<NormalizedDownloadResult> results = parseHtmlResults(html, searchUri, siteBaseUrl, criteria, config, preferredFormat);
                if (!results.isEmpty()) {
                    return results;
                }
            } catch (DownloadSourceException e) {
                lastFailure = e;
            } catch (Exception e) {
                lastFailure = e;
            }
        }

        if (!atLeastOneRendered && lastFailure != null) {
            throw new DownloadSourceException("Anna HTML search failed for all configured domains: " + lastFailure.getMessage(), lastFailure);
        }
        return List.of();
    }

    private URI buildSearchUri(AnnasArchiveHtmlConfig config, String searchUrl, String term, DownloadFormat format) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(searchUrl)
                .queryParam(config.queryParam(), term);
        if (config.formatParam() != null && format != null && format != DownloadFormat.UNKNOWN && !format.extension().isBlank()) {
            builder.queryParam(config.formatParam(), format.extension());
        }
        return builder.build().encode().toUri();
    }

    private List<NormalizedDownloadResult> parseHtmlResults(String html,
                                                            URI searchUri,
                                                            String siteBaseUrl,
                                                            DownloadSearchCriteria criteria,
                                                            AnnasArchiveHtmlConfig config,
                                                            DownloadFormat preferredFormat) throws Exception {
        Document document = Jsoup.parse(html, siteBaseUrl);
        Elements links = document.select(config.resultLinkSelector());
        if (links.isEmpty() || GENERIC_MD5_LINK_SELECTOR.equals(config.resultLinkSelector())) {
            Elements preferredLinks = document.select(DEFAULT_RESULT_LINK_SELECTOR);
            if (!preferredLinks.isEmpty()) {
                links = preferredLinks;
            }
        }
        if (links.isEmpty() && !GENERIC_MD5_LINK_SELECTOR.equals(config.resultLinkSelector())) {
            links = document.select(GENERIC_MD5_LINK_SELECTOR);
        }

        List<NormalizedDownloadResult> results = new ArrayList<>();
        Set<String> seenMd5 = new LinkedHashSet<>();
        int limit = Math.min(Math.max(1, criteria.getMaxResults()), config.maxResults());

        for (Element link : links) {
            if (results.size() >= limit) {
                break;
            }
            String href = link.attr("href");
            String md5 = extractMd5(href);
            if (md5 == null || !seenMd5.add(md5)) {
                continue;
            }

            String detailsUrl = absoluteUrl(link, href, siteBaseUrl);
            String rawText = resultText(link);
            String title = firstNonBlank(titleFrom(link, rawText), rawText, md5);
            DownloadFormat resultFormat = formatFromText(rawText, preferredFormat);

            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("md5", md5);
            raw.put("href", href);
            raw.put("detailsUrl", detailsUrl);
            raw.put("searchUrl", searchUri.toString());
            raw.put("text", rawText);

            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(md5)
                    .title(truncate(title, 512))
                    .authors(authorsFrom(link, criteria, rawText, title))
                    .publishedYear(firstYear(rawText))
                    .language(languageFrom(rawText))
                    .format(resultFormat)
                    .contentKind(Optional.ofNullable(criteria.getContentKind()).orElse(DownloadContentKind.BOOK))
                    .acquisitionType(DownloadAcquisitionType.EXTERNAL_STACKS)
                    .sizeBytes(firstSize(rawText))
                    .downloadUrl(null)
                    .detailsUrl(detailsUrl)
                    .requiresFlareSolverr(config.requiresFlareSolverr())
                    .rawJson(objectMapper.writeValueAsString(raw))
                    .build());
        }

        return results;
    }

    private AnnasArchiveHtmlConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "annasArchiveApi");
        String configuredBaseUrl = firstNonBlank(
                node.path("baseUrl").asText(null),
                node.path("searchUrl").asText(null),
                configReader.firstText(source, "baseUrl", null),
                configReader.firstText(source, "searchUrl", null),
                DEFAULT_SEARCH_URL
        );
        if (looksLikeStacksSearchEndpoint(configuredBaseUrl)) {
            throw new DownloadSourceException("Stacks is a downloader queue and does not expose a keyword search endpoint. Configure annasArchiveApi.baseUrl with an Anna web domain, then keep stacks.baseUrl for MD5 downloads.");
        }

        String searchPath = firstNonBlank(node.path("searchPath").asText(null), DEFAULT_SEARCH_PATH);
        List<String> searchUrls = searchUrls(node, configuredBaseUrl, searchPath);
        DownloadFormat defaultFormat = DownloadFormat.fromText(firstNonBlank(node.path("defaultFormat").asText(null), "epub"));
        if (defaultFormat == DownloadFormat.UNKNOWN) {
            defaultFormat = DownloadFormat.EPUB;
        }

        return new AnnasArchiveHtmlConfig(
                searchUrls,
                blankToNull(node.path("queryParam").asText("q")),
                blankToNull(node.path("formatParam").asText("ext")),
                defaultFormat,
                Math.max(1, node.path("maxResults").asInt(50)),
                firstNonBlank(node.path("resultLinkSelector").asText(null), DEFAULT_RESULT_LINK_SELECTOR),
                node.path("requiresFlareSolverr").asBoolean(true)
        );
    }

    private List<String> searchUrls(JsonNode node, String configuredBaseUrl, String searchPath) {
        List<String> candidates = new ArrayList<>();
        addSearchUrl(candidates, configuredBaseUrl, searchPath);
        for (String baseUrl : stringList(node.path("baseUrls"))) {
            addSearchUrl(candidates, baseUrl, searchPath);
        }
        for (String baseUrl : stringList(node.path("fallbackBaseUrls"))) {
            addSearchUrl(candidates, baseUrl, searchPath);
        }
        for (String searchUrl : stringList(node.path("fallbackSearchUrls"))) {
            addSearchUrl(candidates, searchUrl, searchPath);
        }
        if (node.path("useDefaultFallbacks").asBoolean(true)) {
            for (String baseUrl : DEFAULT_FALLBACK_BASE_URLS) {
                addSearchUrl(candidates, baseUrl, searchPath);
            }
        }
        return candidates.stream()
                .filter(value -> !looksLikeStacksSearchEndpoint(value))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
    }

    private void addSearchUrl(List<String> candidates, String value, String searchPath) {
        if (value == null || value.isBlank()) {
            return;
        }
        candidates.add(normalizeSearchUrl(value.trim(), searchPath));
    }

    private String normalizeSearchUrl(String baseUrl, String searchPath) {
        String value = trimTrailingSlash(baseUrl);
        URI uri = URI.create(value);
        String path = Optional.ofNullable(uri.getPath()).orElse("");
        if (path.isBlank() || "/".equals(path)) {
            return value + "/" + trimLeadingSlash(searchPath);
        }
        return value;
    }

    private String siteBaseUrl(String searchUrl) {
        URI uri = URI.create(searchUrl);
        String scheme = firstNonBlank(uri.getScheme(), "https");
        return scheme + "://" + uri.getAuthority();
    }

    private String extractMd5(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        Matcher matcher = MD5_PATTERN.matcher(href);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private String absoluteUrl(Element link, String href, String siteBaseUrl) {
        String absolute = link.absUrl("href");
        if (absolute != null && !absolute.isBlank()) {
            return absolute;
        }
        return URI.create(siteBaseUrl).resolve(href).toString();
    }

    private String resultText(Element link) {
        String best = textWithLines(link);
        Element container = resultContainer(link);
        if (container != null) {
            String candidate = textWithLines(container);
            if (candidate.length() > best.length() && candidate.length() <= 2_000) {
                best = candidate;
            }
        }
        return compact(best);
    }

    private Element resultContainer(Element link) {
        Element parent = link.parent();
        for (int i = 0; i < 5 && parent != null; i++) {
            if (parent.select(GENERIC_MD5_LINK_SELECTOR).size() == 1) {
                return parent;
            }
            parent = parent.parent();
        }
        return link.parent();
    }

    private String titleFrom(Element link, String fallbackText) {
        for (String line : textLines(link)) {
            String candidate = compact(line);
            if (!candidate.isBlank() && !looksLikeMetadata(candidate)) {
                return candidate;
            }
        }
        return fallbackText;
    }

    private List<String> textLines(Element element) {
        String text = element.wholeText();
        if (text == null || text.isBlank()) {
            text = element.text();
        }
        return text.lines()
                .map(this::compact)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String textWithLines(Element element) {
        String text = element.wholeText();
        if (text == null || text.isBlank()) {
            text = element.text();
        }
        return text == null ? "" : text;
    }

    private boolean looksLikeMetadata(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.matches(".*\\b(epub|pdf|mobi|azw3|fb2|djvu|cbz|cbr)\\b.*")
                || normalized.matches(".*\\b(kb|kib|mb|mib|gb|gib)\\b.*")
                || normalized.startsWith("language:")
                || normalized.startsWith("extension:")
                || normalized.startsWith("file:");
    }

    private List<String> authorsFrom(Element link, DownloadSearchCriteria criteria, String text, String title) {
        String configuredAuthor = firstNonBlank(criteria.getAuthor());
        if (configuredAuthor != null && containsIgnoreCase(text, configuredAuthor)) {
            return List.of(configuredAuthor);
        }
        Element container = resultContainer(link);
        if (container != null) {
            List<String> authors = container.select("a[href^=/search?q=], a[href*='/search?q=']")
                    .stream()
                    .filter(candidate -> !candidate.select("[class*=user-edit]").isEmpty())
                    .map(Element::text)
                    .map(this::cleanAuthor)
                    .filter(author -> !author.isBlank())
                    .filter(author -> !author.equalsIgnoreCase(title))
                    .distinct()
                    .limit(3)
                    .toList();
            if (!authors.isEmpty()) {
                return authors;
            }
        }
        List<String> lines = text.lines()
                .map(this::compact)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.equalsIgnoreCase(title))
                .filter(line -> !looksLikeMetadata(line))
                .filter(line -> line.length() <= 160)
                .toList();
        return lines.isEmpty() ? List.of() : List.of(lines.getFirst());
    }

    private String cleanAuthor(String value) {
        String author = compact(value)
                .replaceAll("(?i)^author\\s*[:\\-]\\s*", "")
                .replaceAll("\\s*\\[[^]]*]\\s*$", "")
                .trim();
        int separator = author.indexOf(';');
        if (separator > 0) {
            author = author.substring(0, separator).trim();
        }
        Matcher matcher = Pattern.compile("^([^,]{2,80}),\\s*(.{2,80})$").matcher(author);
        if (matcher.matches()) {
            author = compact(matcher.group(2) + " " + matcher.group(1));
        }
        return author;
    }

    private DownloadFormat formatFromText(String text, DownloadFormat fallback) {
        DownloadFormat fromText = DownloadFormat.fromText(text);
        return fromText == DownloadFormat.UNKNOWN ? fallback : fromText;
    }

    private Integer firstYear(String text) {
        Matcher matcher = YEAR_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String languageFrom(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("french") || normalized.contains("français") || normalized.contains("francais")) {
            return "fr";
        }
        if (normalized.contains("english")) {
            return "en";
        }
        return null;
    }

    private Long firstSize(String text) {
        Matcher matcher = SIZE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            double amount = Double.parseDouble(matcher.group(1).replace(",", "."));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            long multiplier = switch (unit) {
                case "kb", "kib" -> 1024L;
                case "mb", "mib" -> 1024L * 1024L;
                case "gb", "gib" -> 1024L * 1024L * 1024L;
                default -> 1L;
            };
            long bytes = (long) (amount * multiplier);
            return bytes > 0 ? bytes : null;
        } catch (NumberFormatException ignored) {
            return null;
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

    private String compact(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim();
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && needle != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private boolean looksLikeStacksSearchEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("/api/search") && (normalized.contains("stacks") || normalized.contains(":7788"));
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String trimLeadingSlash(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
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

    private record AnnasArchiveHtmlConfig(List<String> searchUrls,
                                          String queryParam,
                                          String formatParam,
                                          DownloadFormat defaultFormat,
                                          int maxResults,
                                          String resultLinkSelector,
                                          boolean requiresFlareSolverr) {
    }
}
