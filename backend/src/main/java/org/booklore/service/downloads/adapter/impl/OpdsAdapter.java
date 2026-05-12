package org.booklore.service.downloads.adapter.impl;

import lombok.RequiredArgsConstructor;
import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.DownloadSourceAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.booklore.service.downloads.dto.NormalizedDownloadResult;
import org.booklore.service.downloads.exception.DownloadSourceException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OpdsAdapter implements DownloadSourceAdapter {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public DownloadSourceType sourceType() {
        return DownloadSourceType.OPDS;
    }

    @Override
    public List<NormalizedDownloadResult> search(DownloadSourceEntity source, DownloadSearchCriteria criteria) {
        try {
            JsonNode config = objectMapper.readTree(source.getCredentialsJson() == null ? "{}" : source.getCredentialsJson());
            String url = buildSearchUrl(config, criteria);
            HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(3, config.path("timeoutSeconds").asInt(20))))
                    .header("Accept", "application/atom+xml, application/xml, text/xml")
                    .header("User-Agent", "BookLore-Downloads")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DownloadSourceException("OPDS search failed with HTTP status " + response.statusCode());
            }
            return parseAtomFeed(response.body(), criteria);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadSourceException("OPDS search interrupted", e);
        } catch (Exception e) {
            if (e instanceof DownloadSourceException sourceException) throw sourceException;
            throw new DownloadSourceException("OPDS search failed: " + e.getMessage(), e);
        }
    }

    private String buildSearchUrl(JsonNode config, DownloadSearchCriteria criteria) {
        String template = config.path("searchUrlTemplate").asText(null);
        String baseUrl = config.path("baseUrl").asText(null);
        String encoded = URLEncoder.encode(criteria.effectiveQuery(), StandardCharsets.UTF_8);
        if (template != null && !template.isBlank()) {
            return template.replace("{query}", encoded);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new DownloadSourceException("OPDS source requires credentials_json.baseUrl or credentials_json.searchUrlTemplate");
        }
        return baseUrl;
    }

    private List<NormalizedDownloadResult> parseAtomFeed(String xml, DownloadSearchCriteria criteria) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList entries = document.getElementsByTagName("entry");
        List<NormalizedDownloadResult> results = new ArrayList<>(entries.getLength());
        for (int i = 0; i < entries.getLength(); i++) {
            Node node = entries.item(i);
            if (!(node instanceof Element entry)) continue;
            Element acquisition = acquisitionLink(entry);
            if (acquisition == null) continue;
            String href = acquisition.getAttribute("href");
            if (href == null || href.isBlank()) continue;
            String type = acquisition.getAttribute("type");

            results.add(NormalizedDownloadResult.builder()
                    .sourceResultId(text(entry, "id"))
                    .title(firstNonBlank(text(entry, "title"), "Untitled"))
                    .authors(authorNames(entry))
                    .format(formatFromMime(type, href))
                    .contentKind(criteria.getContentKind())
                    .acquisitionType(DownloadAcquisitionType.OPDS_ACQUISITION)
                    .downloadUrl(href)
                    .detailsUrl(firstNonBlank(text(entry, "id"), href))
                    .rawJson(null)
                    .build());
        }
        return results;
    }

    private Element acquisitionLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        Element fallback = null;
        for (int i = 0; i < links.getLength(); i++) {
            Node node = links.item(i);
            if (!(node instanceof Element link)) continue;
            String rel = link.getAttribute("rel");
            if (rel != null && rel.contains("acquisition")) {
                return link;
            }
            if (fallback == null && link.hasAttribute("href")) {
                fallback = link;
            }
        }
        return fallback;
    }

    private List<String> authorNames(Element entry) {
        NodeList authors = entry.getElementsByTagName("author");
        List<String> names = new ArrayList<>(authors.getLength());
        for (int i = 0; i < authors.getLength(); i++) {
            Node node = authors.item(i);
            if (node instanceof Element author) {
                String name = text(author, "name");
                if (name != null && !name.isBlank()) names.add(name.trim());
            }
        }
        return names;
    }

    private DownloadFormat formatFromMime(String mime, String href) {
        String value = mime == null ? "" : mime.toLowerCase();
        if (value.contains("epub")) return DownloadFormat.EPUB;
        if (value.contains("pdf")) return DownloadFormat.PDF;
        if (value.contains("cbz")) return DownloadFormat.CBZ;
        return DownloadFormat.fromFileName(href).orElse(DownloadFormat.UNKNOWN);
    }

    private String text(Element item, String tagName) {
        NodeList list = item.getElementsByTagName(tagName);
        if (list.getLength() == 0 || list.item(0) == null) return null;
        return list.item(0).getTextContent();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
