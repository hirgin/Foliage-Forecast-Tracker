-- Searchable places: towns and cities, each resolved to its forecast hexagon.
--
-- Populated from the full GeoNames US file, filtered by feature code rather
-- than population. Foliage destinations are small or unpopulated: Killington
-- and Grafton record zero, Manchester 740, Woodstock 871, Stowe 4,314. Any
-- population threshold tidy enough to be useful would have left Burlington and
-- dropped everywhere people actually drive to see leaves.
--
-- Natural features are included for the same reason -- Mount Mansfield and
-- Smugglers' Notch are destinations in a way most towns are not.
--
-- Separate statements, not one ALTER: TiDB does not apply a multi-clause
-- schema change atomically. See V7.
CREATE TABLE place (
    geoname_id  INT          NOT NULL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    state_code  CHAR(2)      NULL,
    latitude    DOUBLE       NOT NULL,
    longitude   DOUBLE       NOT NULL,
    population  INT          NOT NULL DEFAULT 0,

    -- TOWN, PARK, FOREST, MOUNTAIN, NOTCH -- mapped from GeoNames feature
    -- codes. Selecting by code rather than population is what keeps Killington
    -- (population 0) and Grafton (0) in the list.
    kind        VARCHAR(10)  NOT NULL,

    -- The res 6 cell containing this place. Computed from the coordinates
    -- alone, so it is known whether or not that cell is in the grid yet: as
    -- the grid expands, places light up without re-ingesting.
    h3          BIGINT       NOT NULL,

    ingested_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Serves "which places are in the loaded grid", the export's only query.
CREATE INDEX place_h3_idx ON place (h3);

-- Serves ranking: a search for "man" should surface Manchester before a
-- hamlet of 40 people.
CREATE INDEX place_population_idx ON place (population DESC);

-- Prefix search: "man" -> Manchester. Left-anchored LIKE can use this index.
CREATE INDEX place_name_idx ON place (name);
