-- Climatological normals: the multi-year mean for a calendar day at a cell.
--
-- Separate from weather_daily on purpose. ADR-0005 uses climatology as a
-- *fallback* for days past the forecast horizon, and the upsert there
-- deliberately lets forecast and observed rows replace it. But the drought
-- term needs a normal to compare against even on days we have observations
-- for -- exactly the days where the fallback has been overwritten.
--
-- One is "our best estimate for this specific day", the other is "what this
-- day is usually like". Conflating them leaves the anomaly with nothing to
-- measure against.
CREATE TABLE weather_normal (
    h3             BIGINT      NOT NULL,
    -- Calendar day as MM-DD: normals are year-independent by definition.
    month_day      CHAR(5)     NOT NULL,
    resolution     TINYINT     NOT NULL,

    tmax_c         DECIMAL(4,1) NULL,
    tmin_c         DECIMAL(4,1) NULL,
    precip_mm      DECIMAL(5,1) NULL,

    years_averaged TINYINT     NOT NULL,
    computed_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (h3, month_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
