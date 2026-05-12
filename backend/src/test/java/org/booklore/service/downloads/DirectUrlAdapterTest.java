package org.booklore.service.downloads;

import org.booklore.model.entity.DownloadSourceEntity;
import org.booklore.model.enums.DownloadAcquisitionType;
import org.booklore.model.enums.DownloadContentKind;
import org.booklore.model.enums.DownloadFormat;
import org.booklore.model.enums.DownloadSourceType;
import org.booklore.service.downloads.adapter.impl.DirectUrlAdapter;
import org.booklore.service.downloads.dto.DownloadSearchCriteria;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirectUrlAdapterTest {

    private final DirectUrlAdapter adapter = new DirectUrlAdapter(new ObjectMapper());

    @Test
    void search_directCbzUrl_returnsDirectFileResult() {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("direct")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson("{}")
                .build();
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl("https://example.test/series/chapter.cbz")
                .title("Chapter")
                .contentKind(DownloadContentKind.COMIC)
                .build();

        var results = adapter.search(source, criteria);

        assertEquals(1, results.size());
        assertEquals(DownloadAcquisitionType.DIRECT_FILE, results.getFirst().getAcquisitionType());
        assertEquals(DownloadFormat.CBZ, results.getFirst().getFormat());
        assertFalse(results.getFirst().isRequiresFlareSolverr());
    }

    @Test
    void search_magnetUrlWithFlareSolverr_marksTorrentAndFlareSolverr() {
        DownloadSourceEntity source = DownloadSourceEntity.builder()
                .name("direct")
                .type(DownloadSourceType.DIRECT_URL)
                .configJson("{\"useFlareSolverr\":true}")
                .build();
        DownloadSearchCriteria criteria = DownloadSearchCriteria.builder()
                .directUrl("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
                .title("Torrent Result")
                .preferredFormats(List.of(DownloadFormat.CBZ))
                .build();

        var results = adapter.search(source, criteria);

        assertEquals(1, results.size());
        assertEquals(DownloadAcquisitionType.TORRENT, results.getFirst().getAcquisitionType());
        assertEquals(DownloadFormat.UNKNOWN, results.getFirst().getFormat());
        assertTrue(results.getFirst().isRequiresFlareSolverr());
    }
}
