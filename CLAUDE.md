# CLAUDE.md

Working notes for agents on this repo. Keep this current — it is the fastest
path back into context.

## What this is

A US fall-foliage forecast. Weather and terrain data feed a phenology model
that scores forest colour progression on an H3 hexagon grid; a React map
renders it over time.

## Layout

- `backend/` — Spring Boot 3 + Kotlin. Gradle, run from inside this directory.
- `frontend/` — Vite + React 18, **plain JavaScript, not TypeScript**.
- `docs/adr/` — architecture decisions. Read these before changing structure.
- `data/` — derived grid/terrain artifacts. Large raw inputs are gitignored.

## Commands

```bash
cd backend && ./gradlew bootRun      # API on :8080
cd backend && ./gradlew test         # unit + integration tests
npm run dev --prefix frontend        # UI on :5173, proxies /api to :8080
```

## Conventions

- **No JPA.** Plain JDBC. Hot paths are batched multi-row INSERTs and wide
  range scans; an ORM helps with neither. See ADR-0003.
- **MySQL 8, no spatial extension.** The H3 index *is* the spatial index. All
  geometry work happens once in the offline bootstrap using JTS.
  See ADR-0002 and ADR-0004.
- **Migrations run through `DatabaseBootstrap`**, not Flyway autoconfiguration,
  so an unreachable database degrades the service instead of killing startup.
  If you add a migration, that is still the only place it runs.
- Every ingest job writes an `ingest_run` row. Jobs must be idempotent and
  resumable — the historical backfill is too large to restart from zero.
- New weather sources implement `WeatherSource` and nothing downstream changes.
  That seam is deliberate; do not leak source-specific types past it. The same
  applies to `CanopySource` and `ElevationSource`.
- **Every phase ships tests.** No Testcontainers — there is no Docker here, so
  a container-backed test could never run. Instead, split every external
  client into a thin transport half and a **pure parsing function**, and test
  the parser against a real response captured into
  `src/test/resources/fixtures/`. See `docs/testing.md`.
- Parsers must degrade, not throw: a short, empty, or malformed response
  becomes nulls so that one bad batch cannot abort a whole bootstrap.

## Gotchas

- Kotlin's Spring plugin opens all `@Component` classes, so `private set` on a
  mutable property fails to compile. Mark the property `final`.
- **There is no local database.** No Docker, no local MySQL. Development runs
  against a hosted MySQL-compatible instance, configured in
  `application-local.yml` (gitignored — copy the `.example`) and activated with
  `--spring.profiles.active=local`. TiDB listens on **4000**, not 3306, and
  hosted MySQL requires `sslMode=REQUIRED`.
- Because ingest crosses the network, batches must be large and jobs must be
  resumable — a dropped connection mid-backfill cannot restart from zero.
  Bulk loads rely on `rewriteBatchedStatements=true` on the JDBC URL; without
  it ingest is ~10x slower.
- **Never persist per-factor model contributions.** It nearly triples the
  forecast table and blows the 5 GiB free tier. `explain` recomputes them for
  a single cell on demand. See ADR-0004.
- **The forecast horizon is 16 days; the season is ~75.** Most of the season
  is climatology until it draws close. Never present a `CLIMATOLOGY` day as a
  forecast in the UI. See ADR-0005.
- Open-Meteo returns a JSON **object** for one coordinate and an **array** for
  several. Code that handles only the array form works until a batch of size
  one appears at the end of a run.
- **Open-Meteo meters by request weight, not count.** Seven 100-coordinate
  batches in under two seconds trips its minutely limit. Clients retry on 429
  via `RetryPolicy`; do not remove that, and do not raise batch sizes hoping
  for fewer round trips -- weight is what is metered.
- The preview tooling resolves `.claude/launch.json` from the session's
  original project root. Use absolute paths in `runtimeArgs`; a relative
  `-p backend` silently builds whatever tree the preview process starts in.
- MySQL specifics: timestamps are `DATETIME(6)` (TIMESTAMP dies in 2038),
  indexed strings must be `VARCHAR` not `TEXT`, and all stored times are UTC.
- Java 23 is the toolchain. Node is 18.16, so Vite stays on 5.x.

## Phases

0. ✅ Foundations — skeletons, health endpoint, status page.
1. Grid — H3 res 6 tiling of CONUS, canopy mask, terrain attributes.
2. Weather pipeline — Open-Meteo at res 5, bulk load, audit.
3. Forecast model — phenology scoring, lapse-rate downscale to res 6.
4. Map experience — hexagon choropleth, time slider, detail panel.
5. Polish — caching, "How It Works", deploy.
6. NOAA GRIB2 — HRRR at native res 6, swapped in behind `WeatherSource`.
