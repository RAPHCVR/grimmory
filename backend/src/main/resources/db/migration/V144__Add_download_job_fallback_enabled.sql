ALTER TABLE download_job
    ADD COLUMN fallback_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER confidence_threshold;
