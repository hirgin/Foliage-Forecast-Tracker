-- Every pipeline job writes a row here: what ran, over what window, how much
-- it moved, and whether it finished. This is what makes ingest runs
-- observable and safely resumable.
--
-- All timestamps are UTC. DATETIME rather than TIMESTAMP deliberately:
-- MySQL's TIMESTAMP tops out in 2038, and this table is meant to be an
-- archive.
CREATE TABLE ingest_run (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source       VARCHAR(64)  NOT NULL,
    job          VARCHAR(64)  NOT NULL,
    window_start DATE         NULL,
    window_end   DATE         NULL,
    started_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at  DATETIME(6)  NULL,
    rows_written BIGINT       NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'running',
    error        TEXT         NULL,
    CONSTRAINT ingest_run_status_check
        CHECK (status IN ('running', 'succeeded', 'failed', 'cancelled')),
    -- Indexed, so VARCHAR rather than TEXT: MySQL cannot index TEXT without
    -- a prefix length.
    INDEX ingest_run_job_started_idx (job, started_at DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
