# ADR-0002: Forecast on H3 hexagons, not counties; no geospatial extension

**Status:** accepted

## Context

Foliage maps conventionally colour counties. Counties are political boundaries
with no relationship to forest phenology: a single Colorado county spans over
4,000 ft of elevation, which is several weeks of difference in peak timing.
Averaging that into one colour discards the strongest sub-regional signal there
is.

Separately, this removes the only reason the project would have needed a
geospatial database extension at all.

## Decision

Forecast on **H3 hexagons at resolution 6** (~3 km edge, ~36 km²), masked to
forested cells by NLCD tree canopy cover. Roughly 80,000 cells cover CONUS.

Resolution 6 is chosen because it exactly matches NOAA HRRR's native 3 km grid.
Every cell can therefore eventually carry a real forecast rather than an
interpolated one — going finer would mean inventing precision the weather
models do not have.

**No geospatial extension is needed** (PostGIS, MySQL spatial types, or
otherwise). The H3 index *is* the spatial index:

- Cell → neighbours, parents, children: arithmetic on the index, no database.
- Cell → polygon: computed client-side from the index by `h3-js`.
- Zoom aggregation: `GROUP BY` on a parent-index column, not a spatial join.

The only genuine geometry work — tiling CONUS, masking to canopy, assigning
state FIPS — happens once in an offline bootstrap using JTS, a pure-JVM library
with no native dependencies.

## Consequences

- There are **zero runtime spatial queries**. The database stores `bigint` indices
  and numbers, and ordinary B-tree indexes serve every query -- which is what
  makes the database choice in ADR-0004 essentially free.
- No native extensions, so local setup is a bare `CREATE DATABASE`.
- Cells are equal-area and equal-shape, so the map has no visual bias toward
  large western counties.
- Political rollups (state, county) still work, but as denormalised columns on
  each cell rather than as the primary unit.
- Cost: users think in place names, not hexagons. A place-name search endpoint
  is therefore required, not optional.
