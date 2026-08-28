-- The forecast grid: one row per H3 resolution-6 hexagon (~36 km, ~3 km edge).
--
-- Cells are stored for the whole tiled area, including unforested ones, with
-- canopy_pct recorded rather than applied. Masking is a query-time predicate,
-- not an ingest-time filter -- retuning the forest threshold must not require
-- re-sampling the canopy raster, which is the expensive part.
--
-- H3 indexes are 64-bit. Cell-mode indexes never set the top bit, so they fit
-- a signed BIGINT without reinterpretation.
CREATE TABLE cell (
    h3           BIGINT      NOT NULL PRIMARY KEY,
    resolution   TINYINT     NOT NULL,

    -- Denormalised ancestors. Zoom aggregation is a GROUP BY on one of these
    -- columns rather than a spatial join, and the weather join uses res 5
    -- while Open-Meteo is the source. See ADR-0002.
    parent_res5  BIGINT      NOT NULL,
    parent_res4  BIGINT      NOT NULL,
    parent_res3  BIGINT      NOT NULL,

    centroid_lat DOUBLE      NOT NULL,
    centroid_lon DOUBLE      NOT NULL,

    -- Terrain. Nullable because the grid is tiled first and enriched after;
    -- a cell with NULL canopy_pct has not been sampled yet.
    elevation_m  SMALLINT    NULL,
    canopy_pct   TINYINT     NULL,

    state_fips   CHAR(2)     NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    INDEX cell_parent_res5_idx (parent_res5),
    INDEX cell_state_idx (state_fips),
    -- Serves "every forested cell in a state", the map's primary query.
    INDEX cell_state_canopy_idx (state_fips, canopy_pct)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
