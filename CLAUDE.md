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
  That seam is deliberate; do not leak source-specific types past it.
- **Terrain rasters are read as tiles, never point by point.** Elevation,
  canopy and forest type all group cells by the degree tile they fall in and
  fetch each tile once. Point sampling works at state scale and cannot finish
  nationally. See ADR-0007.
- **A cell with no forest type scores at the maple-beech baseline**, exactly as
  the whole map did before the species term existed. That fallback is what lets
  the term ship against a partly surveyed grid; do not turn a missing type into
  an error. See ADR-0009. The same
  applies to `CanopySource` and `ElevationSource` — both were swapped from
  point sampling to bulk rasters with no change downstream. See ADR-0007.
- **Terrain is read as tiles, not points.** Group cells by the raster tile they
  fall in and fetch each tile once. Point-sampling CONUS was ~71 h of elevation
  and ~10 h of canopy; tiles make elevation effectively free (649 Vermont cells
  in 664 ms). If you add a terrain source, do the same.
- **Sample a tile and release it inside the fetch**, never collect tiles and
  read them afterwards. A canopy tile decodes to ~13 MB and a large state spans
  dozens; peak memory must stay at `threads x tile` however much ground one
  call covers.
- **Every phase ships tests.** No Testcontainers — there is no Docker here, so
  a container-backed test could never run. Instead, split every external
  client into a thin transport half and a **pure parsing function**, and test
  the parser against a real response captured into
  `src/test/resources/fixtures/`. See `docs/testing.md`.
- Parsers must degrade, not throw: a short, empty, or malformed response
  becomes nulls so that one bad batch cannot abort a whole bootstrap. The same
  goes for a failed tile.
- **When you replace a data source, test the two against each other**, not the
  new one against hardcoded values — it can drift in step with the service.
  The equivalence is the claim being made; assert it. See `docs/testing.md`.

## Gotchas

- Kotlin's Spring plugin opens all `@Component` classes, so `private set` on a
  mutable property fails to compile. Mark the property `final`.
- **`FOLIAGE_ADMIN_ENABLED=true` is required for the bootstrap endpoints.**
  They are `@ConditionalOnProperty` and simply 404 without it, which reads as
  a routing bug rather than a missing flag.
- **Check nothing is already on :8080 before trusting a run.** A stale backend
  from an earlier session keeps answering `/actuator/health` while `bootRun`
  fails with "port already in use" in a log you are not reading — so a
  bootstrap appears to work and silently exercises the *old* code.
- **The canopy ImageServer throttles concurrent renders.** More threads is not
  more throughput: 6 threads measured *worse* per tile than 4. Tune against
  measured tiles-per-second, not intuition.
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
- **The forest type raster returns individual FIA *types*, not only groups.**
  841, 402 and ~200 others are types nested inside groups; resolve a code to
  the highest group at or below it. It also returns 63693 over Canada and the
  Great Lakes, which is not a code at all -- `CellSampling.isForestCode` states
  the valid domain rather than trusting the raster.
- **Forest type is categorical, so it takes a mode, never a mean.** 800 is
  maple-beech, 900 is aspen-birch, and their average of 850 is not a forest.
- **Open-Meteo's daily quota resets at UTC midnight, not local.** That is 17:00
  Pacific, so an evening run already spends tomorrow's allowance. The log says
  which window was hit -- hourly and daily are different waits.
- **MapLibre allows one zoom-based subexpression per expression.** Nesting
  `step` inside `step` is rejected and blanks the whole map. Clearing a layout
  property with `undefined` fails validation the same way; delete the key.
- **MapLibre label collisions favour later layers**, and colliding labels are
  dropped rather than moved. `symbol-sort-key` decides which survives; without
  it the winner is whatever order the tile happened to supply.
- **The benchmark is the map published at `6379bd3`, model `0.3.0-photoperiod`,
  approved by the user.** Measure changes against it, in numbers rather than
  impressions: Vermont ~14 days of spread in peak dates across 60 neighbouring
  cells (N Georgia ~16); peak order Vermont 27 Sep, Michigan 9 Oct, Colorado
  10 Oct, Georgia 31 Oct, Louisiana 22 Nov; the deep south finishing during
  December; no empty hexes. Constants: `S_PEAK` 100, floor 1.25 below 11.5 h.
  A change that improves peak-date error while dropping Vermont below ~12 days
  is a regression however good the fit looks.
- **Most fixes are not national, even when the code change is.** Only a
  constant that moves every cell's timing needs the whole country rescored;
  a fix aimed at data gaps, one forest type, or one region needs the states
  that have them. A state is ~400k rows against 15M, so find the affected
  states first -- `admin/thin-weather` for weather gaps, `admin/forest-type`
  for composition, `admin/peak-spread` for timing outliers -- and rescore
  those. Reaching for a national pass by reflex is how an evening's budget
  went on 80 cells.
- **Validate a model change on five states before touching the country.**
  Vermont, Michigan, Georgia, Louisiana, Colorado -- one per region, roughly
  500k rows against 15M for a national pass. Check peak *dates* and the local
  *spread* of peak dates among neighbouring cells; a change can hold every
  published window and still flatten the map, and fitting for date accuracy
  alone is what did exactly that. A national rescore is the most expensive
  thing this project can do and three were spent in one evening on model
  versions that a five-state check would have rejected.
- **Peak timing is read against `S_PEAK`, so raising it flattens the map.**
  Local differences between hexagons are what elevation and weather produce,
  and they shrink in proportion to the threshold. Vermont's spread went 14
  days to 10 to 7 as it was raised from 100 to 200 to 260. See ADR-0010.
- **Check coverage with a grouped count, not by sampling cells.** Spot checks
  have given a wrong answer about this project's own state three times --
  including "the species term is barely reaching anything" when it was reaching
  a quarter of Minnesota.

## Phases

0. ✅ Foundations — skeletons, health endpoint, status page.
1. Grid — H3 res 6 tiling of CONUS, canopy mask, terrain attributes.
2. Weather pipeline — Open-Meteo at res 5, bulk load, audit.
3. Forecast model — phenology scoring, lapse-rate downscale to res 6.
4. Map experience — hexagon choropleth, time slider, detail panel.
5. Polish — caching, "How It Works", deploy.
5b. ✅ Species — FIA forest type per cell from USFS BIGMAP, scaling `S_PEAK`.
   CONUS surveyed. Validated against 46,424 USA-NPN observations. See ADR-0009.
6. NOAA GRIB2 — HRRR at native res 6, swapped in behind `WeatherSource`.
