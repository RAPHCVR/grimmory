package org.booklore.service.downloads;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSequenceNumberType;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadCanonicalResolver {

    private static final Pattern ISBN_LIKE = Pattern.compile("(?<!\\d)(?:97[89][\\s-]?)?\\d[\\d\\s-]{8,16}[\\dXx](?!\\d)");
    private static final Pattern EXPLICIT_SEQUENCE_MARKER = Pattern.compile(
            "(?iu)(?:\\b(?:vol(?:ume)?|v|t(?:ome|omo)?|issue|iss|ch(?:apter)?|chap(?:itre)?|chapter|episode|ep)\\.?\\s*0*\\d{1,5}(?:\\.\\d+)?\\b|#\\s*0*\\d{1,5}(?:\\.\\d+)?\\b)"
    );
    private static final Pattern BARE_TRAILING_NUMBER = Pattern.compile("(?iu)^.+?\\s+0*\\d{1,5}(?:\\.\\d+)?$");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> TOKEN_STOP_WORDS = Set.of(
            "the", "a", "an", "and", "of", "for", "to", "in", "on",
            "le", "la", "les", "un", "une", "des", "de", "du", "d", "et",
            "vol", "volume", "v", "tome", "tomo", "chapter", "chapitre", "chap", "ch",
            "episode", "ep", "book", "livre", "manga", "comic", "webtoon", "manhwa", "manhua"
    );
    private static final Set<String> QUERY_KIND_HINT_WORDS = Set.of(
            "book", "books", "livre", "livres",
            "manga", "mangas",
            "comic", "comics",
            "webtoon", "webtoons",
            "manhwa", "manhwas",
            "manhua", "manhuas"
    );
    private static final List<String> TITLE_LANGUAGE_ORDER = List.of("en", "fr", "ja-ro", "ja", "ko", "zh", "es", "de", "it");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${booklore.downloads.resolver.enabled:true}")
    boolean enabled = true;
    @Value("${booklore.downloads.resolver.timeout-seconds:4}")
    int timeoutSeconds = 4;
    @Value("${booklore.downloads.resolver.provider-limit:5}")
    int providerLimit = 5;
    @Value("${booklore.downloads.resolver.minimum-confidence:0.52}")
    double minimumConfidence = 0.52D;

    @Value("${booklore.downloads.resolver.openlibrary.enabled:true}")
    boolean openLibraryEnabled = true;
    @Value("${booklore.downloads.resolver.openlibrary.base-url:https://openlibrary.org}")
    String openLibraryBaseUrl = "https://openlibrary.org";

    @Value("${booklore.downloads.resolver.google-books.enabled:true}")
    boolean googleBooksEnabled = true;
    @Value("${booklore.downloads.resolver.google-books.base-url:https://www.googleapis.com/books/v1}")
    String googleBooksBaseUrl = "https://www.googleapis.com/books/v1";

    @Value("${booklore.downloads.resolver.mangadex.enabled:true}")
    boolean mangaDexEnabled = true;
    @Value("${booklore.downloads.resolver.mangadex.base-url:https://api.mangadex.org}")
    String mangaDexBaseUrl = "https://api.mangadex.org";

    @Value("${booklore.downloads.resolver.webtoons.enabled:true}")
    boolean webtoonsEnabled = true;
    @Value("${booklore.downloads.resolver.webtoons.search-url-templates:https://www.webtoons.com/en/search?keyword={query},https://www.webtoons.com/search?keyword={query}}")
    String webtoonsSearchUrlTemplates = "https://www.webtoons.com/en/search?keyword={query},https://www.webtoons.com/search?keyword={query}";

    @Value("${booklore.downloads.resolver.webtoons.asura.enabled:true}")
    boolean asuraWebtoonsEnabled = true;
    @Value("${booklore.downloads.resolver.webtoons.asura.search-url-templates:https://asurascans.com/comics?search={query},https://comicasura.net/?s={query}}")
    String asuraSearchUrlTemplates = "https://asurascans.com/comics?search={query},https://comicasura.net/?s={query}";

    @Value("${booklore.downloads.resolver.comicvine.enabled:false}")
    boolean comicVineEnabled = false;
    @Value("${booklore.downloads.resolver.comicvine.base-url:https://comicvine.gamespot.com/api}")
    String comicVineBaseUrl = "https://comicvine.gamespot.com/api";
    @Value("${booklore.downloads.resolver.comicvine.api-key:}")
    String comicVineApiKey = "";

    public DownloadSearchCriteria resolve(DownloadSearchCriteria criteria) {
        if (criteria == null || !isBlank(criteria.getDirectUrl())) {
            return criteria;
        }
        if (criteria.getCanonicalSelection() != null) {
            return applySelection(criteria, criteria.getCanonicalSelection());
        }
        if (!enabled) {
            return criteria;
        }

        String term = providerSearchTerm(canonicalInput(criteria));
        if (isBlank(term)) {
            return criteria;
        }

        try {
            List<Candidate> candidates = collectCandidates(criteria, term);
            Optional<Candidate> best = candidates.stream()
                    .filter(candidate -> candidate.confidence() >= minimumConfidence)
                    .max((left, right) -> Double.compare(left.confidence(), right.confidence()));
            if (best.isEmpty()) {
                return criteria;
            }
            DownloadSearchCriteria resolved = applyCandidate(criteria, best.get());
            log.debug("Canonical resolver selected {} candidate '{}' with confidence {} for '{}'",
                    best.get().provider(), best.get().displayTitle(), best.get().confidence(), criteria.effectiveQuery());
            return resolved;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Canonical resolver interrupted for '{}'", criteria.effectiveQuery(), e);
            return criteria;
        } catch (Exception e) {
            log.debug("Canonical resolver skipped for '{}': {}", criteria.effectiveQuery(), e.getMessage(), e);
            return criteria;
        }
    }

    public List<CanonicalCandidate> resolveCandidates(DownloadSearchCriteria criteria) {
        if (!enabled || criteria == null || criteria.getCanonicalSelection() != null || !isBlank(criteria.getDirectUrl())) {
            return List.of();
        }

        String term = providerSearchTerm(canonicalInput(criteria));
        if (isBlank(term)) {
            return List.of();
        }

        try {
            List<Candidate> candidates = collectCandidates(criteria, term).stream()
                    .filter(candidate -> candidate.confidence() >= minimumConfidence)
                    .sorted((left, right) -> Double.compare(right.confidence(), left.confidence()))
                    .toList();
            int limit = candidateLimit(criteria);
            List<CanonicalCandidate> resolvedCandidates = new ArrayList<>();
            for (Candidate candidate : candidates) {
                for (CanonicalCandidate canonicalCandidate : candidateVariants(criteria, candidate)) {
                    resolvedCandidates.add(canonicalCandidate);
                    if (resolvedCandidates.size() >= limit) {
                        return resolvedCandidates;
                    }
                }
            }
            return resolvedCandidates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Canonical resolver candidate lookup interrupted for '{}'", criteria.effectiveQuery(), e);
            return List.of();
        } catch (Exception e) {
            log.debug("Canonical resolver candidate lookup skipped for '{}': {}", criteria.effectiveQuery(), e.getMessage(), e);
            return List.of();
        }
    }

    private List<Candidate> collectCandidates(DownloadSearchCriteria criteria, String term) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        DownloadContentKind requested = requestedKind(criteria);
        boolean sequential = likelySequentialArt(criteria);

        if (mangaDexEnabled && (requested == DownloadContentKind.MANGA || requested == DownloadContentKind.AUTO && sequential)) {
            candidates.addAll(resolveMangaDex(term));
        }
        if (webtoonsEnabled && requested == DownloadContentKind.WEBTOON) {
            candidates.addAll(resolveWebtoons(term));
        }
        if (asuraWebtoonsEnabled && requested == DownloadContentKind.WEBTOON) {
            candidates.addAll(resolveAsuraWebtoons(term));
        }
        if (comicVineEnabled && !isBlank(comicVineApiKey) && requested == DownloadContentKind.COMIC) {
            candidates.addAll(resolveComicVine(term));
        }
        if (requested == DownloadContentKind.BOOK || requested == DownloadContentKind.AUTO || requested == DownloadContentKind.COMIC) {
            if (openLibraryEnabled) {
                candidates.addAll(resolveOpenLibrary(term));
            }
            if (googleBooksEnabled) {
                candidates.addAll(resolveGoogleBooks(term));
            }
        }
        return candidates;
    }

    private int candidateLimit(DownloadSearchCriteria criteria) {
        int requestedLimit = criteria == null ? 0 : criteria.getMaxResults();
        if (requestedLimit <= 0) {
            return 10;
        }
        return Math.min(requestedLimit, 10);
    }

    private List<Candidate> resolveOpenLibrary(String term) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(openLibraryBaseUrl)
                .path("/search.json")
                .queryParam("q", term)
                .queryParam("limit", boundedProviderLimit())
                .queryParam("fields", "key,title,author_name,isbn,language,first_publish_year")
                .build()
                .encode()
                .toUri();
        Optional<JsonNode> root = fetchJson(uri);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode doc : array(root.get().path("docs"))) {
            String title = text(doc.path("title"));
            if (isBlank(title)) {
                continue;
            }
            String author = firstArrayText(doc.path("author_name"));
            String isbn = firstIsbn(doc.path("isbn"), term);
            String coverUrl = openLibraryCoverUrl(doc, isbn);
            String detailsUrl = openLibraryDetailsUrl(doc);
            String year = text(doc.path("first_publish_year"));
            candidates.add(new Candidate(
                    "openlibrary",
                    DownloadContentKind.BOOK,
                    title,
                    author,
                    isbn,
                    null,
                    bookScore(term, title, author, isbn),
                    coverUrl,
                    detailsUrl,
                    null,
                    year,
                    Map.of()
            ));
        }
        return candidates;
    }

    private List<Candidate> resolveGoogleBooks(String term) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(googleBooksBaseUrl)
                .path("/volumes")
                .queryParam("q", term)
                .queryParam("maxResults", boundedProviderLimit())
                .queryParam("printType", "books")
                .build()
                .encode()
                .toUri();
        Optional<JsonNode> root = fetchJson(uri);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode item : array(root.get().path("items"))) {
            JsonNode info = item.path("volumeInfo");
            String title = text(info.path("title"));
            if (isBlank(title)) {
                continue;
            }
            String author = firstArrayText(info.path("authors"));
            String isbn = googleBooksIsbn(info.path("industryIdentifiers"), term);
            String coverUrl = googleBooksCoverUrl(info.path("imageLinks"));
            String detailsUrl = text(info.path("infoLink"));
            String description = text(info.path("description"));
            String year = yearFromDate(text(info.path("publishedDate")));
            candidates.add(new Candidate(
                    "google-books",
                    DownloadContentKind.BOOK,
                    title,
                    author,
                    isbn,
                    null,
                    bookScore(term, title, author, isbn),
                    coverUrl,
                    detailsUrl,
                    description,
                    year,
                    Map.of()
            ));
        }
        return candidates;
    }

    private List<Candidate> resolveMangaDex(String term) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(mangaDexBaseUrl)
                .path("/manga")
                .queryParam("title", term)
                .queryParam("limit", boundedProviderLimit())
                .queryParam("includes[]", "author")
                .queryParam("includes[]", "artist")
                .queryParam("includes[]", "cover_art")
                .queryParam("order[relevance]", "desc")
                .queryParam("contentRating[]", "safe")
                .queryParam("contentRating[]", "suggestive")
                .queryParam("contentRating[]", "erotica")
                .queryParam("contentRating[]", "pornographic")
                .build()
                .encode()
                .toUri();
        Optional<JsonNode> root = fetchJson(uri);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode manga : array(root.get().path("data"))) {
            String title = localizedTitle(manga.path("attributes"));
            if (isBlank(title)) {
                continue;
            }
            String author = relationshipNames(manga, Set.of("author", "artist"));
            String mangaId = text(manga.path("id"));
            String coverFile = relationshipAttribute(manga, "cover_art", "fileName");
            String coverUrl = mangaDexCoverUrl(mangaId, coverFile);
            String detailsUrl = isBlank(mangaId) ? null : "https://mangadex.org/title/" + mangaId;
            String description = localizedDescription(manga.path("attributes"));
            String year = text(manga.path("attributes").path("year"));
            candidates.add(new Candidate(
                    "mangadex",
                    DownloadContentKind.MANGA,
                    title,
                    author,
                    null,
                    title,
                    score(term, title, author),
                    coverUrl,
                    detailsUrl,
                    description,
                    year,
                    Map.of()
            ));
        }
        return candidates;
    }

    private List<Candidate> resolveWebtoons(String term) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String template : splitCsv(webtoonsSearchUrlTemplates)) {
            String searchUrl = template.replace("{query}", URLEncoder.encode(term, StandardCharsets.UTF_8));
            Optional<String> body = fetchText(URI.create(searchUrl));
            if (body.isEmpty()) {
                continue;
            }
            Document document = Jsoup.parse(body.get(), searchUrl);
            for (Element anchor : document.select("a[href*=title_no][href*=/list]")) {
                String href = anchor.absUrl("href");
                if (isBlank(href) || !seen.add(normalizeUrl(href))) {
                    continue;
                }
                String title = firstNonBlank(textOf(anchor, ".title"), anchor.attr("title"), textOf(anchor, ".subj"));
                if (isBlank(title)) {
                    continue;
                }
                String author = textOf(anchor, ".author");
                String coverUrl = firstNonBlank(attrOf(anchor, "img", "src"), attrOf(anchor, "img", "data-src"));
                candidates.add(new Candidate(
                        "webtoons",
                        DownloadContentKind.WEBTOON,
                        title,
                        author,
                        null,
                        title,
                        score(term, title, author),
                        coverUrl,
                        href,
                        null,
                        null,
                        Map.of()
                ));
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }
        return candidates;
    }

    private List<Candidate> resolveAsuraWebtoons(String term) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String template : splitCsv(asuraSearchUrlTemplates)) {
            String searchUrl = template.replace("{query}", URLEncoder.encode(term, StandardCharsets.UTF_8));
            Optional<String> body = fetchText(URI.create(searchUrl));
            if (body.isEmpty()) {
                continue;
            }
            Document document = Jsoup.parse(body.get(), searchUrl);
            for (Element anchor : document.select("a[href*=/comics/]")) {
                String href = normalizeUrl(anchor.absUrl("href"));
                if (!isAsuraSeriesUrl(href) || !seen.add(href)) {
                    continue;
                }
                String title = firstAsuraTitle(
                        anchor.attr("title"),
                        attrOf(anchor, "img[alt]", "alt"),
                        textOf(anchor, ".line-clamp-2"),
                        textOf(anchor, ".text-sm"),
                        textOf(anchor, "h3"),
                        textOf(anchor, "h4"),
                        anchor.text(),
                        titleFromAsuraUrl(href)
                );
                if (isBlank(title)) {
                    continue;
                }
                double confidence = score(term, title, null);
                if (confidence < 0.40D) {
                    continue;
                }
                String coverUrl = firstNonBlank(attrOf(anchor, "img", "src"), attrOf(anchor, "img", "data-src"));
                candidates.add(new Candidate(
                        "asura",
                        DownloadContentKind.WEBTOON,
                        title,
                        null,
                        null,
                        title,
                        confidence,
                        coverUrl,
                        href,
                        null,
                        null,
                        Map.of()
                ));
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }
        return candidates;
    }

    private List<Candidate> resolveComicVine(String term) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(comicVineBaseUrl)
                .path("/search/")
                .queryParam("api_key", comicVineApiKey)
                .queryParam("format", "json")
                .queryParam("resources", "volume")
                .queryParam("query", term)
                .queryParam("limit", boundedProviderLimit())
                .build()
                .encode()
                .toUri();
        Optional<JsonNode> root = fetchJson(uri);
        if (root.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode result : array(root.get().path("results"))) {
            String title = text(result.path("name"));
            if (isBlank(title)) {
                continue;
            }
            String publisher = text(result.path("publisher").path("name"));
            String coverUrl = firstNonBlank(text(result.path("image").path("small_url")), text(result.path("image").path("medium_url")), text(result.path("image").path("super_url")));
            String detailsUrl = text(result.path("site_detail_url"));
            String description = stripHtml(text(result.path("description")));
            String year = yearFromDate(text(result.path("start_year")));
            candidates.add(new Candidate(
                    "comicvine",
                    DownloadContentKind.COMIC,
                    title,
                    publisher,
                    null,
                    title,
                    score(term, title, publisher),
                    coverUrl,
                    detailsUrl,
                    description,
                    year,
                    Map.of()
            ));
        }
        return candidates;
    }

    private Optional<JsonNode> fetchJson(URI uri) throws Exception {
        HttpResponse<String> response = send(uri, "application/json");
        if (response.statusCode() < 200 || response.statusCode() > 299 || isBlank(response.body())) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readTree(response.body()));
    }

    private Optional<String> fetchText(URI uri) throws Exception {
        HttpResponse<String> response = send(uri, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if (response.statusCode() < 200 || response.statusCode() > 299 || isBlank(response.body())) {
            return Optional.empty();
        }
        return Optional.of(response.body());
    }

    private HttpResponse<String> send(URI uri, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Accept", accept)
                .header("User-Agent", "BookLore-Downloads")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private DownloadSearchCriteria applySelection(DownloadSearchCriteria criteria, DownloadSearchCriteria.CanonicalSelection selection) {
        DownloadSearchCriteria.DownloadSearchCriteriaBuilder builder = criteria.toBuilder();
        DownloadContentKind selectedKind = selection.contentKind() == null ? DownloadContentKind.AUTO : selection.contentKind();
        DownloadSequenceNumberType selectedSequenceType = selection.sequenceNumberType() == null
                ? DownloadSequenceNumberType.AUTO
                : selection.sequenceNumberType();
        boolean sequential = selectedKind.isSequentialArt() || likelySequentialArt(criteria);
        String selectedTitle = firstNonBlank(selection.resolvedTitle(), selection.title());
        String selectedAuthor = firstNonBlank(selection.resolvedAuthor(), selection.author());
        String selectedIsbn = firstNonBlank(selection.resolvedIsbn(), selection.isbn());
        String selectedSeriesName = firstNonBlank(selection.resolvedSeriesName(), selection.seriesName(), sequential ? selectedTitle : null);

        if (!isBlank(selectedTitle)) {
            builder.title(selectedTitle);
        }
        if (sequential && !isBlank(selectedSeriesName)) {
            builder.seriesName(selectedSeriesName);
        }
        if (!isBlank(selectedAuthor)) {
            builder.author(selectedAuthor);
        }
        if (!isBlank(selectedIsbn)) {
            builder.isbn(selectedIsbn);
        }
        if (!selectedKind.isAuto()) {
            builder.contentKind(selectedKind);
        }
        if (selection.seriesNumber() != null) {
            builder.seriesNumber(selection.seriesNumber());
        }
        if (!selectedSequenceType.isAuto()) {
            builder.sequenceNumberType(selectedSequenceType);
        }

        String selectedQuery = firstNonBlank(
                selection.query(),
                sequential ? selectedSeriesName : compactJoin(selectedTitle, selectedAuthor),
                criteria.getQuery()
        );
        if (!isBlank(selectedQuery)) {
            builder.query(selectedQuery);
        }

        DownloadSearchCriteria resolved = builder.build();
        return resolved.toBuilder()
                .canonicalSelection(normalizeSelection(selection, resolved))
                .build();
    }

    private DownloadSearchCriteria applyCandidate(DownloadSearchCriteria criteria, Candidate candidate) {
        return applyCandidate(criteria, candidate, null);
    }

    private DownloadSearchCriteria applyCandidate(DownloadSearchCriteria criteria, Candidate candidate, DownloadSequenceNumberType sequenceOverride) {
        DownloadSearchCriteria.DownloadSearchCriteriaBuilder builder = criteria.toBuilder();
        boolean sequential = candidate.contentKind() != null && candidate.contentKind().isSequentialArt() || likelySequentialArt(criteria);

        if (isBlank(criteria.getTitle()) && !isBlank(candidate.title())) {
            builder.title(candidate.title());
        }
        if (sequential && isBlank(criteria.getSeriesName()) && !isBlank(candidate.seriesName())) {
            builder.seriesName(candidate.seriesName());
        }
        if (isBlank(criteria.getAuthor()) && !isBlank(candidate.author())) {
            builder.author(candidate.author());
        }
        if (isBlank(criteria.getIsbn()) && !isBlank(candidate.isbn())) {
            builder.isbn(candidate.isbn());
        }
        if (criteria.getContentKind() == null || criteria.getContentKind().isAuto()) {
            builder.contentKind(candidate.contentKind());
        }
        if (sequenceOverride != null && !sequenceOverride.isAuto()) {
            builder.sequenceNumberType(sequenceOverride);
        }
        String canonicalQuery = canonicalOutputQuery(criteria, candidate, sequential);
        if (!isBlank(canonicalQuery)) {
            builder.query(canonicalQuery);
        }
        DownloadSearchCriteria resolved = builder.build();
        return resolved.toBuilder()
                .canonicalSelection(toSelection(candidate, resolved))
                .build();
    }

    private CanonicalCandidate toCanonicalCandidate(DownloadSearchCriteria criteria, Candidate candidate) {
        return toCanonicalCandidate(criteria, candidate, null);
    }

    private CanonicalCandidate toCanonicalCandidate(DownloadSearchCriteria criteria, Candidate candidate, DownloadSequenceNumberType sequenceOverride) {
        DownloadSearchCriteria resolved = applyCandidate(criteria, candidate, sequenceOverride);
        return new CanonicalCandidate(
                candidate.provider(),
                candidate.contentKind(),
                candidate.title(),
                candidate.author(),
                candidate.isbn(),
                candidate.seriesName(),
                Math.round(candidate.confidence() * 1000D) / 1000D,
                resolved.getQuery(),
                resolved.getTitle(),
                resolved.getAuthor(),
                resolved.getIsbn(),
                resolved.getSeriesName(),
                resolved.getSeriesNumber(),
                resolved.getSequenceNumberType(),
                candidate.coverUrl(),
                candidate.detailsUrl(),
                candidate.description(),
                candidate.year(),
                candidate.extraMetadata()
        );
    }

    private List<CanonicalCandidate> candidateVariants(DownloadSearchCriteria criteria, Candidate candidate) {
        if (!shouldOfferMangaNumberAmbiguity(criteria, candidate)) {
            return List.of(toCanonicalCandidate(criteria, candidate));
        }
        return List.of(
                toCanonicalCandidate(criteria, candidate, DownloadSequenceNumberType.VOLUME),
                toCanonicalCandidate(criteria, candidate, DownloadSequenceNumberType.CHAPTER)
        );
    }

    private boolean shouldOfferMangaNumberAmbiguity(DownloadSearchCriteria criteria, Candidate candidate) {
        if (candidate.contentKind() != DownloadContentKind.MANGA || criteria.getSeriesNumber() == null) {
            return false;
        }
        DownloadContentKind requested = requestedKind(criteria);
        if (requested != DownloadContentKind.AUTO && requested != DownloadContentKind.MANGA) {
            return false;
        }
        String original = firstNonBlank(criteria.getOriginalQuery(), criteria.getQuery(), criteria.effectiveQuery());
        return !isBlank(original)
                && BARE_TRAILING_NUMBER.matcher(original.trim()).matches()
                && !EXPLICIT_SEQUENCE_MARKER.matcher(original).find();
    }

    private DownloadSearchCriteria.CanonicalSelection toSelection(Candidate candidate, DownloadSearchCriteria resolved) {
        return new DownloadSearchCriteria.CanonicalSelection(
                candidate.provider(),
                resolved.getContentKind(),
                candidate.title(),
                candidate.author(),
                candidate.isbn(),
                candidate.seriesName(),
                Math.round(candidate.confidence() * 1000D) / 1000D,
                resolved.getQuery(),
                resolved.getTitle(),
                resolved.getAuthor(),
                resolved.getIsbn(),
                resolved.getSeriesName(),
                resolved.getSeriesNumber(),
                resolved.getSequenceNumberType()
        );
    }

    private DownloadSearchCriteria.CanonicalSelection normalizeSelection(DownloadSearchCriteria.CanonicalSelection selection, DownloadSearchCriteria resolved) {
        return new DownloadSearchCriteria.CanonicalSelection(
                firstNonBlank(selection.provider(), "manual"),
                resolved.getContentKind(),
                firstNonBlank(selection.title(), resolved.getTitle()),
                firstNonBlank(selection.author(), resolved.getAuthor()),
                firstNonBlank(selection.isbn(), resolved.getIsbn()),
                firstNonBlank(selection.seriesName(), resolved.getSeriesName()),
                selection.confidence() == null ? null : Math.round(selection.confidence() * 1000D) / 1000D,
                resolved.getQuery(),
                resolved.getTitle(),
                resolved.getAuthor(),
                resolved.getIsbn(),
                resolved.getSeriesName(),
                resolved.getSeriesNumber(),
                resolved.getSequenceNumberType()
        );
    }

    private String canonicalOutputQuery(DownloadSearchCriteria criteria, Candidate candidate, boolean sequential) {
        String original = criteria.getQuery();
        if (candidate.contentKind() == DownloadContentKind.BOOK) {
            String bookQuery = compactJoin(candidate.title(), candidate.author());
            return isBlank(bookQuery) ? original : bookQuery;
        }
        if (!isBlank(original) && (sequential || looksLikeIsbn(original))) {
            return original;
        }
        return isBlank(original) ? candidate.displayTitle() : original;
    }

    private String canonicalInput(DownloadSearchCriteria criteria) {
        return firstNonBlank(criteria.getSeriesName(), criteria.getTitle(), criteria.getQuery(), criteria.getIsbn(), criteria.effectiveQuery());
    }

    private String providerSearchTerm(String term) {
        if (isBlank(term)) {
            return term;
        }
        String trimmed = term.trim().replaceAll("\\s+", " ");
        String[] rawTokens = trimmed.split("\\s+");
        if (rawTokens.length <= 1) {
            return trimmed;
        }

        List<String> kept = new ArrayList<>();
        for (String rawToken : rawTokens) {
            String token = rawToken.toLowerCase(Locale.ROOT)
                    .replaceAll("^[^\\p{L}\\p{N}]+", "")
                    .replaceAll("[^\\p{L}\\p{N}]+$", "");
            if (!QUERY_KIND_HINT_WORDS.contains(token)) {
                kept.add(rawToken);
            }
        }
        return kept.isEmpty() ? trimmed : compactJoin(kept.toArray(String[]::new));
    }

    private DownloadContentKind requestedKind(DownloadSearchCriteria criteria) {
        return criteria.getContentKind() == null ? DownloadContentKind.AUTO : criteria.getContentKind();
    }

    private boolean likelySequentialArt(DownloadSearchCriteria criteria) {
        DownloadContentKind kind = requestedKind(criteria);
        if (kind.isSequentialArt() || criteria.getSeriesNumber() != null) {
            return true;
        }
        List<DownloadFormat> formats = criteria.getPreferredFormats();
        return formats != null && formats.stream().anyMatch(DownloadFormat::isArchiveComicFormat);
    }

    private double score(String query, String title, String author) {
        double titleScore = tokenScore(query, title);
        double authorScore = tokenScore(query, author);
        double score = Math.max(titleScore, titleScore + Math.min(0.25D, authorScore * 0.25D));
        if (!isBlank(author) && normalized(query).contains(normalized(author))) {
            score += 0.10D;
        }
        return Math.min(0.99D, score);
    }

    private double bookScore(String query, String title, String author, String isbn) {
        if (isbnMatches(query, isbn)) {
            return 0.99D;
        }
        return score(query, title, author);
    }

    private boolean isbnMatches(String left, String right) {
        String cleanLeft = cleanIsbn(left);
        String cleanRight = cleanIsbn(right);
        return !isBlank(cleanLeft) && !isBlank(cleanRight) && cleanLeft.equals(cleanRight);
    }

    private double tokenScore(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0D;
        }
        int intersection = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                intersection++;
            }
        }
        double leftCoverage = intersection / (double) leftTokens.size();
        double rightCoverage = intersection / (double) rightTokens.size();
        double jaccard = intersection / (double) (leftTokens.size() + rightTokens.size() - intersection);
        double score = Math.max(jaccard, leftCoverage * 0.65D + rightCoverage * 0.35D);
        if (leftTokens.size() == 1 && rightTokens.size() > 2 && intersection == 1) {
            return Math.min(score, 0.50D);
        }
        return score;
    }

    private Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            return tokens;
        }
        for (String token : TOKEN_SPLIT.split(normalized)) {
            if (token.length() < 2 || TOKEN_STOP_WORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private String localizedTitle(JsonNode attributes) {
        JsonNode titleNode = attributes.path("title");
        String title = localizedText(titleNode);
        if (!isBlank(title)) {
            return title;
        }
        for (JsonNode altTitle : array(attributes.path("altTitles"))) {
            title = localizedText(altTitle);
            if (!isBlank(title)) {
                return title;
            }
        }
        return null;
    }

    private String localizedText(JsonNode object) {
        if (object == null || object.isMissingNode() || object.isNull()) {
            return null;
        }
        if (object.isTextual()) {
            return text(object);
        }
        for (String language : TITLE_LANGUAGE_ORDER) {
            String value = text(object.path(language));
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String relationshipNames(JsonNode resource, Set<String> types) {
        List<String> names = new ArrayList<>();
        for (JsonNode relationship : array(resource.path("relationships"))) {
            if (!types.contains(relationship.path("type").asText())) {
                continue;
            }
            String name = text(relationship.path("attributes").path("name"));
            if (!isBlank(name) && !names.contains(name)) {
                names.add(name);
            }
        }
        return String.join(", ", names);
    }

    private String googleBooksIsbn(JsonNode identifiers, String preferredIsbn) {
        String first = null;
        String firstIsbn13 = null;
        String preferred = cleanIsbn(preferredIsbn);
        for (JsonNode identifier : array(identifiers)) {
            String value = cleanIsbn(text(identifier.path("identifier")));
            if (isBlank(value)) {
                continue;
            }
            if (!isBlank(preferred) && preferred.equals(value)) {
                return value;
            }
            if (first == null) {
                first = value;
            }
            if (firstIsbn13 == null && value.length() == 13) {
                firstIsbn13 = value;
            }
        }
        return firstIsbn13 == null ? first : firstIsbn13;
    }

    private String firstIsbn(JsonNode values, String preferredIsbn) {
        String first = null;
        String firstIsbn13 = null;
        String preferred = cleanIsbn(preferredIsbn);
        for (JsonNode value : array(values)) {
            String isbn = cleanIsbn(text(value));
            if (isBlank(isbn)) {
                continue;
            }
            if (!isBlank(preferred) && preferred.equals(isbn)) {
                return isbn;
            }
            if (first == null) {
                first = isbn;
            }
            if (firstIsbn13 == null && isbn.length() == 13) {
                firstIsbn13 = isbn;
            }
        }
        return firstIsbn13 == null ? first : firstIsbn13;
    }

    private String cleanIsbn(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.replaceAll("[^0-9Xx]", "").toUpperCase(Locale.ROOT);
    }

    private boolean looksLikeIsbn(String value) {
        return !isBlank(value) && ISBN_LIKE.matcher(value).find();
    }

    private List<JsonNode> array(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(value);
        }
        return values;
    }

    private String firstArrayText(JsonNode node) {
        for (JsonNode value : array(node)) {
            String text = text(value);
            if (!isBlank(text)) {
                return text;
            }
        }
        return null;
    }

    private List<String> splitCsv(String csv) {
        if (isBlank(csv)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : csv.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private String textOf(Element element, String selector) {
        Element selected = element.selectFirst(selector);
        return selected == null ? null : selected.text().trim();
    }

    private String attrOf(Element element, String selector, String attribute) {
        Element selected = element.selectFirst(selector);
        return selected == null ? null : selected.attr(attribute).trim();
    }

    private String openLibraryCoverUrl(JsonNode doc, String isbn) {
        String coverId = text(doc.path("cover_i"));
        if (!isBlank(coverId)) {
            return "https://covers.openlibrary.org/b/id/" + coverId + "-M.jpg";
        }
        if (!isBlank(isbn)) {
            return "https://covers.openlibrary.org/b/isbn/" + isbn + "-M.jpg";
        }
        return null;
    }

    private String openLibraryDetailsUrl(JsonNode doc) {
        String key = text(doc.path("key"));
        if (isBlank(key)) {
            return null;
        }
        String normalized = key.startsWith("/") ? key : "/" + key;
        return trimTrailingSlash(openLibraryBaseUrl) + normalized;
    }

    private String googleBooksCoverUrl(JsonNode imageLinks) {
        return firstNonBlank(
                text(imageLinks.path("thumbnail")),
                text(imageLinks.path("smallThumbnail"))
        );
    }

    private String mangaDexCoverUrl(String mangaId, String coverFile) {
        if (isBlank(mangaId) || isBlank(coverFile)) {
            return null;
        }
        return "https://uploads.mangadex.org/covers/" + mangaId + "/" + coverFile + ".256.jpg";
    }

    private String relationshipAttribute(JsonNode item, String type, String attribute) {
        for (JsonNode relationship : array(item.path("relationships"))) {
            if (type.equals(text(relationship.path("type")))) {
                String value = text(relationship.path("attributes").path(attribute));
                if (!isBlank(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String localizedDescription(JsonNode attributes) {
        JsonNode descriptions = attributes.path("description");
        for (String language : TITLE_LANGUAGE_ORDER) {
            String value = text(descriptions.path(language));
            if (!isBlank(value)) {
                return value;
            }
        }
        for (var property : descriptions.properties()) {
            String value = text(property.getValue());
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String stripHtml(String value) {
        if (isBlank(value)) {
            return null;
        }
        return Jsoup.parse(value).text();
    }

    private String yearFromDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        var matcher = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)").matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean isAsuraSeriesUrl(String href) {
        if (isBlank(href)) {
            return false;
        }
        try {
            URI uri = URI.create(href);
            String path = Optional.ofNullable(uri.getPath()).orElse("").toLowerCase(Locale.ROOT);
            return path.contains("/comics/")
                    && !path.contains("/chapter/")
                    && !path.endsWith("/comics")
                    && !path.endsWith("/comics/");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String cleanAsuraTitle(String value) {
        if (isBlank(value)) {
            return null;
        }
        String cleaned = value
                .replace('\u00a0', ' ')
                .replaceAll("(?i)\\b(chapter|chapitre|episode|ep)\\s*\\d+(?:\\.\\d+)?\\b.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank() || cleaned.chars().noneMatch(Character::isLetter)) {
            return null;
        }
        return cleaned;
    }

    private String firstAsuraTitle(String... values) {
        for (String value : values) {
            String cleaned = cleanAsuraTitle(value);
            if (!isBlank(cleaned)) {
                return cleaned;
            }
        }
        return null;
    }

    private String titleFromAsuraUrl(String href) {
        try {
            String path = URI.create(href).getPath();
            if (isBlank(path)) {
                return null;
            }
            String[] segments = path.split("/");
            String slug = null;
            for (int index = 0; index < segments.length; index++) {
                if ("comics".equalsIgnoreCase(segments[index]) && index + 1 < segments.length) {
                    slug = segments[index + 1];
                    break;
                }
            }
            if (isBlank(slug)) {
                return null;
            }
            slug = slug.replaceAll("-[a-f0-9]{8,}$", "").replace('-', ' ').trim();
            return titleCase(slug);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String titleCase(String value) {
        if (isBlank(value)) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length());
        boolean capitalize = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetter(current)) {
                builder.append(capitalize ? Character.toTitleCase(current) : current);
                capitalize = false;
            } else {
                builder.append(current);
                capitalize = Character.isWhitespace(current);
            }
        }
        return builder.toString().trim();
    }

    private String normalizeUrl(String value) {
        int hashIndex = value.indexOf('#');
        return hashIndex >= 0 ? value.substring(0, hashIndex) : value;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String compactJoin(String... values) {
        return String.join(" ", splitNonBlank(values)).replaceAll("\\s+", " ").trim();
    }

    private List<String> splitNonBlank(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (!isBlank(value)) {
                present.add(value.trim());
            }
        }
        return present;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int boundedProviderLimit() {
        return Math.max(1, Math.min(providerLimit, 10));
    }

    private record Candidate(String provider,
                             DownloadContentKind contentKind,
                             String title,
                             String author,
                             String isbn,
                             String seriesName,
                             double confidence,
                             String coverUrl,
                             String detailsUrl,
                             String description,
                             String year,
                             Map<String, String> extraMetadata) {
        Candidate(String provider,
                  DownloadContentKind contentKind,
                  String title,
                  String author,
                  String isbn,
                  String seriesName,
                  double confidence) {
            this(provider, contentKind, title, author, isbn, seriesName, confidence, null, null, null, null, Map.of());
        }

        Candidate {
            extraMetadata = extraMetadata == null ? Map.of() : extraMetadata;
        }

        String displayTitle() {
            return seriesName != null && !seriesName.isBlank() ? seriesName : title;
        }
    }

    public record CanonicalCandidate(String provider,
                                     DownloadContentKind contentKind,
                                     String title,
                                     String author,
                                     String isbn,
                                     String seriesName,
                                     double confidence,
                                     String query,
                                     String resolvedTitle,
                                     String resolvedAuthor,
                                      String resolvedIsbn,
                                      String resolvedSeriesName,
                                      Float seriesNumber,
                                      DownloadSequenceNumberType sequenceNumberType,
                                      String coverUrl,
                                      String detailsUrl,
                                      String description,
                                      String year,
                                      Map<String, String> extraMetadata) {
    }
}
