# Foliage Forecast

A US fall-foliage planner. Browse a map of the United States, scrub through the
season, and see where colour is forecast to peak — computed on a **3 km
hexagon grid masked to actual forest cover**, not on county averages.

> **This is a model, not an official forecast.** No authoritative dataset
> records when a given place actually peaked, so these predictions cannot be
> rigorously validated. The methodology is documented in full in
> [`docs/model.md`](docs/model.md).

## Why hexagons

Foliage maps conventionally colour counties. Counties are political boundaries
with no relationship to forest phenology — one Colorado county spans 4,000+ ft
of elevation, which is several weeks of difference in peak timing. Averaging
that into a single colour throws away the strongest signal available.

This project forecasts on H3 resolution 6 hexagons (~3 km, ~36 km²) masked to
forested land: about **80,000 cells**, each with its own elevation, canopy
density, and forest composition. Resolution 6 is not arbitrary — it matches
NOAA HRRR's native 3 km grid, so every cell can carry a real forecast rather
than an interpolated one.

See [ADR-0002](docs/adr/0002-h3-hexagons-not-counties.md).

## Stack

| Layer | Choice |
|---|---|
| Backend | Kotlin, Spring Boot 3, plain JDBC ([ADR-0003](docs/adr/0003-jdbc-not-jpa.md)) |
| Database | MySQL 8, Flyway — **no spatial extension**, the H3 index is the spatial index ([ADR-0004](docs/adr/0004-mysql.md)) |
| Frontend | React 18 + Vite (JavaScript), deck.gl `H3HexagonLayer` over raster tiles |
| Weather | Open-Meteo, swapping to NOAA HRRR GRIB2 from public S3 |

## Running it

Requires JDK 21+, Node 18+, and MySQL 8 — either locally or a hosted
MySQL-compatible service.

Create the schema and a user:

```sql
CREATE DATABASE foliage CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'foliage'@'%' IDENTIFIED BY 'foliage';
GRANT ALL PRIVILEGES ON foliage.* TO 'foliage'@'%';
```

Then run the two services:

```bash
cd backend && ./gradlew bootRun
```

```bash
npm run dev --prefix frontend
```

The UI is on <http://localhost:5173> and proxies `/api` to the backend on
`:8080`.

### Connecting to a hosted database

Override the connection with environment variables. Managed MySQL services
require TLS, so add `sslMode=REQUIRED`:

```bash
export FOLIAGE_DB_URL="jdbc:mysql://HOST:4000/foliage?rewriteBatchedStatements=true&connectionTimeZone=UTC&sslMode=REQUIRED"
export FOLIAGE_DB_USER="..."
export FOLIAGE_DB_PASSWORD="..."
```

`rewriteBatchedStatements=true` is not optional — it is the bulk-ingest path
(see [ADR-0004](docs/adr/0004-mysql.md)). Without it, ingest is roughly ten
times slower.

## Build status

- [x] **Phase 0** — foundations, health endpoint, status page
- [x] **Phase 1** — H3 grid, canopy masking, terrain attributes *(Vermont: 649 cells)*
- [x] **Phase 2** — weather pipeline (Open-Meteo) *(Vermont: 18,920 cell-days)*
- [x] **Phase 3** — phenology model *(Vermont: 49,324 scored cell-days)*
- [x] **Phase 4** — map experience *(time slider, detail panel, factor breakdown)*
- [~] **Phase 5** — polish *(pages, code-splitting, CI done; deploy pending)*
- [ ] **Phase 6** — NOAA GRIB2 pipeline

## Pages

| Route | |
|---|---|
| `#/` | The map — time slider, stage ramp, per-cell detail |
| `#/how-it-works` | The model, its provenance rules, and what it cannot do |
| `#/about-the-build` | How this was built, and the bugs that only surfaced against real data |

The map is code-split: deck.gl and h3-js load only on the map route, so the
content pages ship **60 KB gzipped** rather than 353 KB.

## Testing

```bash
cd backend && ./gradlew test
```

Unit tests plus fixture-backed parser tests, with live-database tests gated
behind opt-in configuration. The strategy — and what deliberately is *not*
tested — is documented in [`docs/testing.md`](docs/testing.md).

## Data sources

Provenance and licensing for every input is in
[`docs/data-sources.md`](docs/data-sources.md).
