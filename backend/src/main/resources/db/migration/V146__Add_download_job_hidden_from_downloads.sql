ALTER TABLE download_job
    ADD COLUMN hidden_from_downloads BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_download_job_hidden_created_at
    ON download_job (hidden_from_downloads, created_at);
