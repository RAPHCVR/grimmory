CREATE TABLE download_source
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    type             VARCHAR(40)  NOT NULL,
    credentials_json JSON,
    config_json      JSON,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    priority         INT          NOT NULL DEFAULT 100,
    created_at       TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_download_source_name (name)
);

CREATE TABLE download_search
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    query                  VARCHAR(512) NOT NULL,
    title                  VARCHAR(512),
    author                 VARCHAR(512),
    isbn                   VARCHAR(32),
    series_name            VARCHAR(512),
    series_number          FLOAT,
    content_kind           VARCHAR(20)  NOT NULL,
    preferred_formats_json JSON,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
    error_message          TEXT,
    created_at             TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_download_search_content_kind (content_kind),
    INDEX idx_download_search_created_at (created_at)
);

CREATE TABLE download_result
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    search_id        BIGINT       NOT NULL,
    source_id        BIGINT       NOT NULL,
    external_id      VARCHAR(512),
    title            VARCHAR(512) NOT NULL,
    authors_json     JSON,
    series_name      VARCHAR(512),
    series_number    FLOAT,
    published_year   INT,
    isbn             VARCHAR(32),
    language         VARCHAR(32),
    format           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    content_kind     VARCHAR(20)  NOT NULL,
    acquisition_type VARCHAR(30)  NOT NULL DEFAULT 'UNKNOWN',
    size_bytes       BIGINT,
    download_url     TEXT,
    details_url      TEXT,
    requires_flare_solverr BOOLEAN NOT NULL DEFAULT FALSE,
    score            INT,
    score_reasons    TEXT,
    raw_json         JSON,
    created_at       TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_download_result_search FOREIGN KEY (search_id) REFERENCES download_search (id) ON DELETE CASCADE,
    CONSTRAINT fk_download_result_source FOREIGN KEY (source_id) REFERENCES download_source (id) ON DELETE CASCADE,
    INDEX idx_download_result_search_score (search_id, score),
    INDEX idx_download_result_source (source_id),
    INDEX idx_download_result_external (external_id)
);

CREATE TABLE download_job
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    search_id              BIGINT      NOT NULL,
    result_id              BIGINT      NOT NULL,
    source_id              BIGINT      NOT NULL,
    status                 VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    progress_percent       INT                  DEFAULT 0,
    confidence_score       INT,
    auto_finalize          BOOLEAN     NOT NULL DEFAULT FALSE,
    confidence_threshold   INT         NOT NULL DEFAULT 90,
    target_library_id      BIGINT,
    target_library_path_id BIGINT,
    staging_dir            TEXT,
    part_file_path         TEXT,
    external_task_id       VARCHAR(255),
    external_task_type     VARCHAR(64),
    staged_file_path       TEXT,
    delivered_file_path    TEXT,
    error_message          TEXT,
    created_at             TIMESTAMP            DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP            DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_progress_at       TIMESTAMP NULL,
    completed_at           TIMESTAMP NULL,
    CONSTRAINT fk_download_job_search FOREIGN KEY (search_id) REFERENCES download_search (id) ON DELETE CASCADE,
    CONSTRAINT fk_download_job_result FOREIGN KEY (result_id) REFERENCES download_result (id) ON DELETE CASCADE,
    CONSTRAINT fk_download_job_source FOREIGN KEY (source_id) REFERENCES download_source (id) ON DELETE CASCADE,
    INDEX idx_download_job_status (status),
    INDEX idx_download_job_external_task (external_task_type, external_task_id),
    INDEX idx_download_job_created_at (created_at),
    INDEX idx_download_job_result (result_id)
);
