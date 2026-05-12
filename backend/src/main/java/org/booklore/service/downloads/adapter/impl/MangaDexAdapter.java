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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        String mangaTitle = localizedText(mangaAttributes.path("title"), config.language());
        List<String> authors = relationshipNames(manga, "author");

        List<NormalizedDownloadResult> results = new ArrayList<>();
        for (JsonNode chapter : mangaFeed(config, mangaId, config.chapterLimitPerManga())) {
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
            String chapterTitle = attributes.path("title").asText(null);
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("mangaId", mangaId);
            raw.put("mangaTitle", mangaTitle);
            raw.put("chapterId", chapterId);
            raw.put("chapter", attributes.path("chapter").asText(null));
            raw.put("volume", attributes.path("volume").asText(null));
            raw.put("translatedLanguage", attributes.path("translatedLanguage").asText(config.language()));

            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(chapterId)
                    .title(firstNonBlank(chapterTitle, mangaTitle, "MangaDex Chapter"))
                    .authors(authors)
                    .seriesName(mangaTitle)
                    .seriesNumber(chapterNumber)
                    .language(attributes.path("translatedLanguage").asText(config.language()))
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
        URI uri = UriComponentsBuilder.fromUriString(config.apiBaseUrl())
                .path("/manga")
                .queryParam("title", title)
                .queryParam("limit", limit)
                .queryParam("includes[]", "author")
                .queryParam("includes[]", "artist")
                .queryParam("contentRating[]", "safe")
                .queryParam("contentRating[]", "suggestive")
                .queryParam("contentRating[]", "erotica")
                .queryParam("contentRating[]", "pornographic")
                .build()
                .encode()
                .toUri();
        return getDataArray(uri, config.timeoutSeconds());
    }

    private List<JsonNode> mangaFeed(MangaDexConfig config, String mangaId, int limit) {
        URI uri = UriComponentsBuilder.fromUriString(config.apiBaseUrl())
                .path("/manga/{id}/feed")
                .queryParam("translatedLanguage[]", config.language())
                .queryParam("limit", limit)
                .queryParam("order[chapter]", "asc")
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

    private String localizedText(JsonNode localized, String preferredLanguage) {
        for (String lang : List.of(preferredLanguage, "en", "ja-ro", "ja", "ko", "zh")) {
            String value = localized.path(lang).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "MangaDex";
    }

    private DownloadContentKind contentKind(DownloadContentKind requested) {
        return requested == DownloadContentKind.MANGA || requested == DownloadContentKind.WEBTOON || requested == DownloadContentKind.COMIC
                ? requested
                : DownloadContentKind.MANGA;
    }

    private MangaDexConfig readConfig(DownloadSourceEntity source) {
        JsonNode node = configReader.firstSection(source, "mangadex");
        String apiBaseUrl = firstNonBlank(node.path("apiBaseUrl").asText(null), configReader.firstText(source, "mangadexApiBaseUrl", DEFAULT_API_BASE_URL));
        String siteBaseUrl = firstNonBlank(node.path("siteBaseUrl").asText(null), DEFAULT_SITE_BASE_URL);
        String language = firstNonBlank(node.path("translatedLanguage").asText(null), node.path("language").asText(null), "en");
        int timeoutSeconds = Math.max(3, node.path("timeoutSeconds").asInt(20));
        int mangaLimit = Math.max(1, node.path("mangaLimit").asInt(3));
        int chapterLimitPerManga = Math.max(1, node.path("chapterLimitPerManga").asInt(100));
        return new MangaDexConfig(trimTrailingSlash(apiBaseUrl), trimTrailingSlash(siteBaseUrl), language, timeoutSeconds, mangaLimit, chapterLimitPerManga);
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

    private record MangaDexConfig(String apiBaseUrl,
                                  String siteBaseUrl,
                                  String language,
                                  int timeoutSeconds,
                                  int mangaLimit,
                                  int chapterLimitPerManga) {
    }
}
