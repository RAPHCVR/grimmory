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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class MangaDexAdapter implements DownloadSourceAdapter {

    private static final String DEFAULT_API_BASE_URL = "https://api.mangadex.org";
    private static final String DEFAULT_SITE_BASE_URL = "https://mangadex.org";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DownloadSourceConfigReader configReader;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.MANGADEX;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        if (!supports(criteria.getContentKind())) {
            return List.of();
        }
        MangaDexConfig config = readConfig(source);
        String term = criteria.getSeriesName() != null && !criteria.getSeriesName().isBlank()
                ? criteria.getSeriesName()
                : criteria.effectiveQuery();
        if (term == null || term.isBlank()) {
            return List.of();
        }

        List<NormalizedDownloadResult> results = new ArrayList<>();
        for (JsonNode manga : searchManga(config, term, Math.max(1, Math.min(criteria.getMaxResults(), config.mangaLimit())))) {
            if (results.size() >= criteria.getMaxResults()) {
                break;
            }
            results.addAll(chapterResults(config, manga, criteria, criteria.getMaxResults() - results.size()));
        }
        return results;
    }

    private List<NormalizedDownloadResult> chapterResults(MangaDexConfig config,
                                                          JsonNode manga,
                                                          DownloadSearchCriteria criteria,
                                                          int remainingSlots) {
        String mangaId = manga.path("id").asText(null);
        if (mangaId == null || mangaId.isBlank()) {
            return List.of();
        }
        JsonNode mangaAttributes = manga.path("attributes");
        String mangaTitle = localizedMangaTitle(mangaAttributes, config.preferredTitleLanguage());
        List<String> authors = relationshipNames(manga, "author");

        List<NormalizedDownloadResult> results = new ArrayList<>();
        Set<String> seenChapters = new HashSet<>();
        int feedLimit = Math.min(100, Math.max(config.chapterLimitPerManga(), remainingSlots) * 5);
        for (JsonNode chapter : mangaFeed(config, mangaId, feedLimit)) {
            JsonNode attributes = chapter.path("attributes");
            Float chapterNumber = parseFloat(attributes.path("chapter").asText(null));
            if (criteria.getSeriesNumber() != null && chapterNumber != null
                    && Math.abs(criteria.getSeriesNumber() - chapterNumber) > 0.001F) {
                continue;
            }
            if (criteria.getSeriesNumber() != null && chapterNumber == null) {
                continue;
            }

            String chapterId = chapter.path("id").asText(null);
            if (chapterId == null || chapterId.isBlank()) {
                continue;
            }
            String chapterKey = chapterKey(attributes, chapterId);
            if (!seenChapters.add(chapterKey)) {
                continue;
            }
            String chapterTitle = attributes.path("title").asText(null);
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("mangaId", mangaId);
            raw.put("mangaTitle", mangaTitle);
            raw.put("chapterId", chapterId);
            raw.put("chapter", attributes.path("chapter").asText(null));
            raw.put("volume", attributes.path("volume").asText(null));
            raw.put("translatedLanguage", attributes.path("translatedLanguage").asText(config.preferredTitleLanguage()));

            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(chapterId)
                    .title(firstNonBlank(chapterTitle, mangaTitle, "MangaDex Chapter"))
                    .authors(authors)
                    .seriesName(mangaTitle)
                    .seriesNumber(chapterNumber)
                    .language(attributes.path("translatedLanguage").asText(config.preferredTitleLanguage()))
                    .format(DownloadFormat.CBZ)
                    .contentKind(contentKind(criteria.getContentKind()))
                    .acquisitionType(DownloadAcquisitionType.MANGADEX_CHAPTER)
                    .downloadUrl(chapterId)
                    .detailsUrl(config.siteBaseUrl() + "/chapter/" + chapterId)
                    .rawJson(writeJson(raw))
                    .build());
            if (results.size() >= remainingSlots) {
                break;
            }
        }
        return results;
    }

    private List<JsonNode> searchManga(MangaDexConfig config, String title, int limit) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.apiBaseUrl())
                .path("/manga")
                .queryParam("title", title)
                .queryParam("limit", limit)
                .queryParam("includes[]", "author")
                .queryParam("includes[]", "artist")
                .queryParam("order[relevance]", "desc")
                .queryParam("contentRating[]", "safe")
                .queryParam("contentRating[]", "suggestive")
                .queryParam("contentRating[]", "erotica")
                .queryParam("contentRating[]", "pornographic");
        addLanguageParams(builder, "availableTranslatedLanguage[]", config.translatedLanguages());
        URI uri = builder
                .build()
                .encode()
                .toUri();
        return getDataArray(uri, config.timeoutSeconds());
    }

    private List<JsonNode> mangaFeed(MangaDexConfig config, String mangaId, int limit) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(config.apiBaseUrl())
                .path("/manga/{id}/feed")
                .queryParam("limit", limit)
                .queryParam("order[chapter]", "asc");
        addLanguageParams(builder, "translatedLanguage[]", config.translatedLanguages());
        URI uri = builder
                .build(mangaId);
        return getDataArray(uri, config.timeoutSeconds());
    }

    private List<JsonNode> getDataArray(URI uri, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("MangaDex request failed with HTTP status " + response.statusCode());
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            List<JsonNode> values = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    values.add(item);
                }
            }
            return values;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("MangaDex request interrupted", e);
        } catch (DownloadSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadSourceException("MangaDex request failed: " + e.getMessage(), e);
        }
    }

    private List<String> relationshipNames(JsonNode resource, String type) {
        List<String> names = new ArrayList<>();
        JsonNode relationships = resource.path("relationships");
        if (!relationships.isArray()) {
            return names;
        }
        for (JsonNode relationship : relationships) {
            if (!type.equals(relationship.path("type").asText())) {
                continue;
            }
            String name = relationship.path("attributes").path("name").asText(null);
            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private String localizedMangaTitle(JsonNode attributes, String preferredLanguage) {
        JsonNode titles = attributes.path("title");
        for (String language : preferredLanguageOrder(preferredLanguage)) {
            String directTitle = textForLanguage(titles, language);
            if (directTitle != null) {
                return directTitle;
            }
        }

        JsonNode altTitles = attributes.path("altTitles");
        if (altTitles.isArray()) {
            for (String language : preferredLanguageOrder(preferredLanguage)) {
                for (JsonNode altTitle : altTitles) {
                    String directAltTitle = textForLanguage(altTitle, language);
                    if (directAltTitle != null) {
                        return directAltTitle;
                    }
                }
            }
        }

        String title = localizedText(titles, preferredLanguage);
        if (title != null) {
            return title;
        }
        if (altTitles.isArray()) {
            for (JsonNode altTitle : altTitles) {
                String localizedAltTitle = localizedText(altTitle, preferredLanguage);
                if (localizedAltTitle != null) {
                    return localizedAltTitle;
                }
            }
        }
        return "MangaDex";
    }

    private String textForLanguage(JsonNode localized, String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String value = localized.path(language).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private String localizedText(JsonNode localized, String preferredLanguage) {
        for (String lang : preferredLanguageOrder(preferredLanguage)) {
            String value = localized.path(lang).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        for (var property : localized.properties()) {
            String value = property.getValue().asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private DownloadContentKind contentKind(DownloadContentKind requested) {
        return requested == DownloadContentKind.MANGA || requested == DownloadContentKind.WEBTOON || requested == DownloadContentKind.COMIC
                ? requested
                : DownloadContentKind.MANGA;
    }

    private boolean supports(DownloadContentKind requested) {
        return requested == null
                || requested == DownloadContentKind.AUTO
                || requested == DownloadContentKind.MANGA
                || requested == DownloadContentKind.WEBTOON
                || requested == DownloadContentKind.COMIC;
    }

    private MangaDexConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "mangadex");
        String apiBaseUrl = firstNonBlank(node.path("apiBaseUrl").asText(null), configReader.firstText(source, "mangadexApiBaseUrl", DEFAULT_API_BASE_URL));
        String siteBaseUrl = firstNonBlank(node.path("siteBaseUrl").asText(null), DEFAULT_SITE_BASE_URL);
        List<String> translatedLanguages = translatedLanguages(source, node);
        String preferredTitleLanguage = firstNonBlank(
                node.path("preferredTitleLanguage").asText(null),
                node.path("titleLanguage").asText(null),
                configReader.firstText(source, "preferredTitleLanguage", null),
                configReader.firstText(source, "titleLanguage", null),
                translatedLanguages.isEmpty() ? null : translatedLanguages.getFirst(),
                "en");
        int timeoutSeconds = Math.max(3, node.path("timeoutSeconds").asInt(20));
        int mangaLimit = Math.max(1, node.path("mangaLimit").asInt(3));
        int chapterLimitPerManga = Math.max(1, node.path("chapterLimitPerManga").asInt(100));
        return new MangaDexConfig(trimTrailingSlash(apiBaseUrl), trimTrailingSlash(siteBaseUrl), translatedLanguages, preferredTitleLanguage, timeoutSeconds, mangaLimit, chapterLimitPerManga);
    }

    private void addLanguageParams(UriComponentsBuilder builder, String parameterName, List<String> languages) {
        for (String language : languages) {
            builder.queryParam(parameterName, language);
        }
    }

    private List<String> translatedLanguages(DownloadSourceEntity source, JsonNode node) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addLanguageValues(values, node.path("translatedLanguages"));
        addLanguageValues(values, node.path("translatedLanguage"));
        addLanguageValues(values, node.path("languages"));
        addLanguageValues(values, node.path("language"));
        addLanguageValues(values, configReader.config(source).path("translatedLanguages"));
        addLanguageValues(values, configReader.config(source).path("translatedLanguage"));
        addLanguageValues(values, configReader.config(source).path("languages"));
        addLanguageValues(values, configReader.config(source).path("language"));
        addLanguageValues(values, configReader.credentials(source).path("translatedLanguages"));
        addLanguageValues(values, configReader.credentials(source).path("translatedLanguage"));
        addLanguageValues(values, configReader.credentials(source).path("languages"));
        addLanguageValues(values, configReader.credentials(source).path("language"));
        values.removeIf(value -> value.equals("*") || value.equalsIgnoreCase("all") || value.equalsIgnoreCase("any"));
        return List.copyOf(values);
    }

    private void addLanguageValues(Set<String> values, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                addLanguageValues(values, item);
            }
            return;
        }
        String raw = node.asText(null);
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String value : raw.split(",")) {
            String language = value.trim().toLowerCase();
            if (!language.isBlank()) {
                values.add(language);
            }
        }
    }

    private List<String> preferredLanguageOrder(String preferredLanguage) {
        LinkedHashSet<String> languages = new LinkedHashSet<>();
        if (preferredLanguage != null && !preferredLanguage.isBlank()) {
            languages.add(preferredLanguage);
        }
        languages.addAll(List.of("en", "fr", "it", "pt-br", "es", "de", "ja-ro", "ko-ro", "zh-ro", "ja", "ko", "zh"));
        return List.copyOf(languages);
    }

    private Float parseFloat(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String chapterKey(JsonNode attributes, String chapterId) {
        String volume = attributes.path("volume").asText("");
        String chapter = attributes.path("chapter").asText("");
        if (!chapter.isBlank()) {
            return volume + ":" + chapter;
        }
        return chapterId;
    }

    private record MangaDexConfig(String apiBaseUrl,
                                  String siteBaseUrl,
                                  List<String> translatedLanguages,
                                  String preferredTitleLanguage,
                                  int timeoutSeconds,
                                  int mangaLimit,
                                  int chapterLimitPerManga) {
    }
}
