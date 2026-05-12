package org.booklore.service.downloads.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.DownloadContentClassifier;
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
    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile("(?i)\\.\\s*(epub|pdf|cbz|cbr|cb7|mobi|azw3?|fb2)(?:\\b|[_?&#])");
    private static final Pattern FILE_NAME_EXTENSION_PATTERN = Pattern.compile("(?i)\\.(?:epub|pdf|cbz|cbr|cb7|mobi|azw3?|fb2)\\b");
    private static final Pattern FILE_EXTENSION_SUFFIX_PATTERN = Pattern.compile("(?i)\\.(?:epub|pdf|cbz|cbr|cb7|mobi|azw3?|fb2)\\b.*$");
    private static final Pattern SEQUENTIAL_TOME_TITLE_PATTERN = Pattern.compile("(?iu)^(.+?)\\s+-\\s+(?:tome|volume|vol\\.?|v)\\s*(\\d+(?:[\\.,]\\d+)?)\\s*[:\\-]?\\s*(.+)$");
    private static final Pattern SEQUENTIAL_VOLUME_TITLE_PATTERN = Pattern.compile("(?iu)^(.+?)\\s+(?:tome|volume|vol\\.?|v)\\s*(\\d+(?:[\\.,]\\d+)?)\\s*[:\\-]?\\s*(.*)$");
    private static final Pattern SEQUENTIAL_DASH_NUMBER_TITLE_PATTERN = Pattern.compile("(?iu)^(.+?)\\s+-\\s*0*(\\d{1,4})(?:\\s*[-:]\\s*(.+))?$");
    private static final Pattern RAW_SERIES_TRAILER_PATTERN = Pattern.compile("(?iu),\\s*([^,]{2,120}),\\s*(\\d+(?:[\\.,]\\d+)?),\\s*(?:1[5-9]\\d{2}|20\\d{2})\\s*$");

    private final FlareSolverrClient flareSolverrClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;
    private final DownloadContentClassifier contentClassifier;

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

        List<DownloadFormat> searchFormats = searchFormats(criteria, config.defaultFormat());
        Exception lastFailure = null;
        boolean atLeastOneRendered = false;
        int limit = Math.min(Math.max(1, criteria.getMaxResults()), config.maxResults());
        for (String searchUrl : config.searchUrls()) {
            List<List<NormalizedDownloadResult>> formatResults = new ArrayList<>();
            for (DownloadFormat searchFormat : searchFormats) {
                URI searchUri = buildSearchUri(config, searchUrl, term, searchFormat);
                String siteBaseUrl = siteBaseUrl(searchUri.toString());
                try {
                    String html = flareSolverrClient.fetchHtml(source, searchUri.toString());
                    atLeastOneRendered = true;
                    List<NormalizedDownloadResult> results = parseHtmlResults(html, searchUri, siteBaseUrl, criteria, config, searchFormat);
                    if (!results.isEmpty()) {
                        formatResults.add(results);
                    }
                } catch (DownloadSourceException e) {
                    lastFailure = e;
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
            List<NormalizedDownloadResult> domainResults = interleaveResults(formatResults, limit);
            if (!domainResults.isEmpty()) {
                return domainResults;
            }
        }

        if (!atLeastOneRendered && lastFailure != null) {
            throw new DownloadSourceException("Anna HTML search failed for all configured domains: " + lastFailure.getMessage(), lastFailure);
        }
        return List.of();
    }

    private List<NormalizedDownloadResult> interleaveResults(List<List<NormalizedDownloadResult>> resultsByFormat, int limit) {
        if (resultsByFormat.isEmpty()) {
            return List.of();
        }
        List<NormalizedDownloadResult> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int maxRows = resultsByFormat.stream().mapToInt(List::size).max().orElse(0);
        for (int row = 0; row < maxRows && merged.size() < limit; row++) {
            for (List<NormalizedDownloadResult> results : resultsByFormat) {
                if (row >= results.size()) {
                    continue;
                }
                NormalizedDownloadResult candidate = results.get(row);
                String key = firstNonBlank(candidate.getSourceResultId(), candidate.getDetailsUrl(), candidate.getTitle());
                if (key != null && seen.add(key)) {
                    merged.add(candidate);
                    if (merged.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return merged;
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
            String rawTitle = cleanDisplayTitle(firstNonBlank(titleFrom(link, rawText), rawText, md5));
            DownloadFormat resultFormat = formatFromText(rawText, preferredFormat);
            DownloadContentKind contentKind = contentClassifier.resolve(
                    criteria.getContentKind(),
                    contentClassifier.infer(sourceType(), "Anna's Archive", rawTitle, null, detailsUrl, null, resultFormat, DownloadAcquisitionType.EXTERNAL_STACKS, rawText),
                    DownloadContentKind.BOOK
            );
            ParsedSequentialMetadata parsed = parseSequentialMetadata(rawTitle, rawText, contentKind);
            String title = firstNonBlank(parsed.title(), rawTitle);

            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("md5", md5);
            raw.put("href", href);
            raw.put("detailsUrl", detailsUrl);
            raw.put("searchUrl", searchUri.toString());
            raw.put("text", rawText);
            if (parsed.seriesName() != null) {
                raw.put("seriesName", parsed.seriesName());
            }
            if (parsed.seriesNumber() != null) {
                raw.put("seriesNumber", parsed.seriesNumber());
            }

            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(md5)
                    .title(truncate(title, 512))
                    .authors(authorsFrom(link, criteria, rawText, title))
                    .seriesName(parsed.seriesName())
                    .seriesNumber(parsed.seriesNumber())
                    .publishedYear(firstYear(rawText))
                    .language(languageFrom(rawText))
                    .format(resultFormat)
                    .contentKind(contentKind)
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
                return cleanDisplayTitle(candidate);
            }
        }
        return cleanDisplayTitle(fallbackText);
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
        return collapseDuplicateTail(author);
    }

    private DownloadFormat formatFromText(String text, DownloadFormat fallback) {
        DownloadFormat fromFileName = firstFileFormat(text);
        if (fromFileName != DownloadFormat.UNKNOWN) {
            return fromFileName;
        }
        DownloadFormat fromText = DownloadFormat.fromText(text);
        return fromText == DownloadFormat.UNKNOWN ? fallback : fromText;
    }

    private DownloadFormat firstFileFormat(String text) {
        if (text == null || text.isBlank()) {
            return DownloadFormat.UNKNOWN;
        }
        Matcher matcher = FILE_EXTENSION_PATTERN.matcher(text);
        if (!matcher.find()) {
            return DownloadFormat.UNKNOWN;
        }
        return DownloadFormat.fromText(matcher.group(1));
    }

    private ParsedSequentialMetadata parseSequentialMetadata(String title,
                                                            String rawText,
                                                            DownloadContentKind contentKind) {
        if (contentKind == null || !contentKind.isSequentialArt()) {
            return new ParsedSequentialMetadata(null, null, null);
        }

        String cleanTitle = cleanDisplayTitle(title);
        Matcher titleMatcher = SEQUENTIAL_TOME_TITLE_PATTERN.matcher(cleanTitle);
        if (titleMatcher.matches()) {
            return new ParsedSequentialMetadata(
                    compact(titleMatcher.group(3)),
                    compact(titleMatcher.group(1)),
                    parseFloat(titleMatcher.group(2))
            );
        }

        titleMatcher = SEQUENTIAL_VOLUME_TITLE_PATTERN.matcher(cleanTitle);
        if (titleMatcher.matches()) {
            String parsedTitle = compact(titleMatcher.group(3));
            return new ParsedSequentialMetadata(
                    parsedTitle.isBlank() ? null : parsedTitle,
                    compact(titleMatcher.group(1)),
                    parseFloat(titleMatcher.group(2))
            );
        }

        titleMatcher = SEQUENTIAL_DASH_NUMBER_TITLE_PATTERN.matcher(cleanTitle);
        if (titleMatcher.matches()) {
            String parsedTitle = compact(titleMatcher.group(3));
            return new ParsedSequentialMetadata(
                    parsedTitle.isBlank() ? null : parsedTitle,
                    compact(titleMatcher.group(1)),
                    parseFloat(titleMatcher.group(2))
            );
        }

        Matcher trailerMatcher = RAW_SERIES_TRAILER_PATTERN.matcher(compact(rawText));
        if (trailerMatcher.find()) {
            return new ParsedSequentialMetadata(
                    null,
                    compact(trailerMatcher.group(1)),
                    parseFloat(trailerMatcher.group(2))
            );
        }

        return new ParsedSequentialMetadata(null, null, null);
    }

    private Float parseFloat(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Float.parseFloat(value.replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private List<DownloadFormat> searchFormats(DownloadSearchCriteria criteria, DownloadFormat defaultFormat) {
        LinkedHashSet<DownloadFormat> formats = new LinkedHashSet<>();
        if (criteria.getPreferredFormats() != null) {
            for (DownloadFormat preferred : criteria.getPreferredFormats()) {
                if (preferred != null && preferred != DownloadFormat.UNKNOWN) {
                    formats.add(preferred);
                }
            }
        }
        if (formats.isEmpty() && defaultFormat != null && defaultFormat != DownloadFormat.UNKNOWN) {
            formats.add(defaultFormat);
        }
        if (formats.isEmpty()) {
            formats.add(DownloadFormat.EPUB);
        }
        return List.copyOf(formats);
    }

    private String compact(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private String cleanDisplayTitle(String value) {
        String title = compact(value);
        if (title.isBlank()) {
            return title;
        }
        String fileName = firstFileNameCandidate(title);
        if (fileName != null) {
            title = fileName;
        }
        title = FILE_EXTENSION_SUFFIX_PATTERN.matcher(title).replaceAll("");
        title = title
                .replace('_', ' ')
                .replaceAll("(?<=[\\p{L}\\d])\\.(?=[\\p{L}\\d])", " ")
                .replaceAll("(?iu)^manga\\s+fr\\s*[-_:]\\s*", "")
                .replaceAll("\\s+", " ");
        return compact(title);
    }

    private String firstFileNameCandidate(String value) {
        String normalized = compact(value).replace('\\', '/');
        Matcher matcher = FILE_NAME_EXTENSION_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        String filePath = normalized.substring(0, matcher.end());
        int slash = filePath.lastIndexOf('/');
        String fileName = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        fileName = compact(fileName);
        return fileName.length() < 5 ? null : fileName;
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

    private String collapseDuplicateTail(String value) {
        String normalized = compact(value);
        String[] tokens = normalized.split("\\s+");
        while (tokens.length >= 2 && tokens[tokens.length - 1].equalsIgnoreCase(tokens[tokens.length - 2])) {
            normalized = normalized.substring(0, normalized.length() - tokens[tokens.length - 1].length()).trim();
            tokens = normalized.split("\\s+");
        }
        return normalized;
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

    private record ParsedSequentialMetadata(String title, String seriesName, Float seriesNumber) {
    }
}
