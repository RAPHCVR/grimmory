ALTER TABLE download_search
    ADD COLUMN canonical_cover_url TEXT NULL AFTER canonical_confidence;
