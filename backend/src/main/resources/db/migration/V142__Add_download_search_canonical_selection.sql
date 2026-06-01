ALTER TABLE download_search
    ADD COLUMN canonical_provider VARCHAR(64) NULL,
    ADD COLUMN canonical_content_kind VARCHAR(20) NULL,
    ADD COLUMN canonical_title VARCHAR(512) NULL,
    ADD COLUMN canonical_author VARCHAR(512) NULL,
    ADD COLUMN canonical_isbn VARCHAR(32) NULL,
    ADD COLUMN canonical_series_name VARCHAR(512) NULL,
    ADD COLUMN canonical_series_number FLOAT NULL,
    ADD COLUMN canonical_sequence_number_type VARCHAR(20) NULL,
    ADD COLUMN canonical_confidence DOUBLE NULL;

CREATE INDEX idx_download_search_canonical_provider ON download_search (canonical_provider);
