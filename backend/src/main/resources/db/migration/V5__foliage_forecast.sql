-- Scored colour progression: one row per res 6 cell per day of the season.
--
-- Deliberately lean. Per-factor model contributions are NOT stored -- they
-- would nearly triple this table and blow the 5 GiB free tier (ADR-0004).
-- The explain endpoint serves one cell at a time and recomputes them, which
-- is trivial work for a single cell.
CREATE TABLE foliage_forecast (
    h3            BIGINT      NOT NULL,
    day           DATE        NOT NULL,

    -- 0-100, one decimal is ample for a colour ramp.
    progression   DECIMAL(4,1) NOT NULL,
    intensity     DECIMAL(4,1) NOT NULL,
    -- Stage is derived from progression, but stored so the map can filter and
    -- group without recomputing bucket boundaries in SQL.
    stage         VARCHAR(12) NOT NULL,
    -- 0-1, from the provenance mix of the days behind the score. See ADR-0005.
    confidence    DECIMAL(3,2) NOT NULL,

    model_version VARCHAR(16) NOT NULL,
    computed_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (h3, day),

    CONSTRAINT foliage_forecast_stage_check
        CHECK (stage IN ('NO_CHANGE','PATCHY','PARTIAL','NEAR_PEAK','PEAK','PAST_PEAK')),

    -- The map's primary query: every cell on one date.
    INDEX foliage_forecast_day_idx (day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
