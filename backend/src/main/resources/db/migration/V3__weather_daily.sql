-- Daily weather per cell, carrying all three provenances in one table.
-- See ADR-0005: the forecast horizon is 16 days but the season is ~75, so a
-- cell-day is observed, forecast, or climatological depending on how far out
-- it is. The phenology model reads one continuous series and uses `kind` only
-- to attach confidence.
--
-- Stored at the resolution the SOURCE is native to -- res 5 while Open-Meteo
-- (ERA5/GFS, 9-25 km) supplies it, res 6 once HRRR does at 3 km. Recording the
-- resolution here is what lets that swap happen without touching the model.
CREATE TABLE weather_daily (
    h3           BIGINT      NOT NULL,
    day          DATE        NOT NULL,
    resolution   TINYINT     NOT NULL,
    kind         VARCHAR(12) NOT NULL,

    tmax_c       DECIMAL(4,1) NULL,
    tmin_c       DECIMAL(4,1) NULL,
    precip_mm    DECIMAL(5,1) NULL,
    radiation_mj DECIMAL(5,2) NULL,

    fetched_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- One row per cell-day regardless of provenance. A later kind replaces an
    -- earlier one in place: climatology becomes forecast, forecast becomes
    -- observed, never the reverse.
    PRIMARY KEY (h3, day),

    CONSTRAINT weather_daily_kind_check
        CHECK (kind IN ('OBSERVED', 'FORECAST', 'CLIMATOLOGY')),

    -- Serves the model's per-cell season scan.
    INDEX weather_daily_day_idx (day),
    INDEX weather_daily_kind_idx (kind, day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
