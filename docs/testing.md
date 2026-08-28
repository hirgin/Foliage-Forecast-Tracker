# Testing strategy

Every phase ships with tests. This documents what is tested where, and — more
importantly — what *cannot* be tested here and why.

## The constraint that shapes everything

**This environment has no Docker.** Testcontainers, the usual answer for
integration-testing a real database, cannot run. Pretending otherwise would
leave a suite that fails the moment anyone runs it, so the dependency was
removed rather than left aspirational.

That pushes the suite into three tiers.

## Tier 1 — Pure unit tests (always run)

No network, no database, no clock. These are the bulk of the suite and the
only tier that gates every build.

| Area | Covered by | What it proves |
|---|---|---|
| H3 tiling and ancestry | `grid/H3GridTest` | Cell counts are the right order of magnitude, resolutions are exact, ancestry is transitive, no res 5 parent exceeds 7 children |
| Per-cell terrain sampling | `ingest/terrain/CellSamplingTest` | Seven samples per hexagon, all strictly inside the boundary; no samples averages to null, not zero |
| Retry and backoff | `ingest/RetryPolicyTest` | Backoff doubles, stops at maxAttempts, and never retries a non-transient error. Sleeping is injected, so the suite stays fast |
| HRRR index parsing | `ingest/weather/hrrr/HrrrIndexParserTest` | Byte ranges are exclusive of the next record, the final record is open-ended, ordering prevents negative ranges, and the same variable at different levels is distinguished |
| HRRR run addressing | `ingest/weather/hrrr/HrrrRunTest` | Bucket keys match what NOAA publishes, and the 48 h / 18 h cycle limits are enforced rather than discovered as a 404 |
| Weather parsing and provenance | `ingest/weather/WeatherParserTest` | Both response shapes parse; missing locations hold their position; nulls stay null rather than becoming zero; the 16-day forecast boundary is exact |
| Season bounds | `ingest/weather/SeasonTest` | 76 inclusive days, stable across leap years, and substantially longer than the forecast horizon |
| Photoperiod | `forecast/PhotoperiodTest` | Checked against *published* day lengths, not just self-consistency: equinox ~12 h at every latitude, Vermont solstices 15.4 h / 8.9 h |
| Lapse-rate downscale | `forecast/LapseRateTest` | 6.5 °C per 1000 m, and a Vermont valley-to-ridge span exceeding 7 °C |
| Phenology model | `forecast/PhenologyModelTest` | Bounded 0–100 under extremes, stages never move backwards, monotonic in every driver, and a calibration test pinning peak to the first half of October |

### Frontend

Vitest, pure logic only — no DOM, no component rendering. The bugs that have
actually occurred here were in colour mapping and date handling, not in markup.

```bash
npm test --prefix frontend
```

| Area | Covered by | What it proves |
|---|---|---|
| Foliage colour ramp | `map/colors.test.js` | Continuity across stage boundaries, monotonic advance within a band, clamping instead of extrapolation, and confidence altering only alpha |
| Packed wire format | `api/packed.test.js` | Magic bytes and truncation rejected rather than read past; the 255 sentinel never decodes as a real value; stage boundaries match the backend exactly; timeline shards extract one cell without disturbing its neighbour |
| Date formatting | `components/dates.test.js` | `yyyy-mm-dd` is parsed as UTC. Local parsing shifts every slider date by one day, and only for users west of Greenwich |

The colour suite carries an explicit regression: progression 79.2 and 85.4 are
both `PEAK`, and must render as **different** colours. Flat-filling the stage
hid a real north-to-south gradient across Vermont — the model was right and the
map was lying. See [`model.md`](model.md).

## Tier 2 — Fixture-backed tests (always run)

External services are **captured once as real responses** into
`src/test/resources/fixtures/`, then parsed offline. This gets the correctness
value of testing against real payloads without the flakiness of hitting a live
service in CI.

| Fixture | Source | Captured |
|---|---|---|
| `nlcd-getsamples.json` | USFS NLCD Tree Canopy ImageServer | 2026-08-27 |
| `openmeteo-elevation.json` | Open-Meteo elevation API | 2026-08-27 |

Every client is therefore split in two: a thin request-issuing part, and a
**pure parsing function** that the tests drive directly. The parsing half is
where the bugs live, and it is fully covered.

**Fixtures go stale.** If a provider changes its response shape, these tests
keep passing while production breaks. Re-capture them when a source is
suspected of drifting; the curl commands are recorded in
[`data-sources.md`](data-sources.md).

## Verified against the live database

Some invariants are only meaningful against a real database, and were checked
by running the pipeline and reconciling the counts:

- **Grid bootstrap is idempotent.** Re-running Vermont produced an identical
  canopy histogram rather than duplicate rows. Re-run again on the bulk raster
  sources (ADR-0007): 649 cells, 649 rows written, and 100% coverage on both
  canopy and elevation.
- **Provenance never downgrades** (ADR-0005). The climatology job attempted
  8,360 rows over the 76-day season for 110 cells. 1,320 of those (Sept 1–12,
  110 cells x 12 days) already held `FORECAST` rows from the forecast job.
  Exactly 7,040 landed as `CLIMATOLOGY` — so not one forecast day was
  overwritten by a long-run average.

These are reconciliations rather than assertions, and belong in tier 3 as
automated checks once a test schema exists.

## Tier 3 — Live integration tests (opt-in)

### HRRR against NOAA

`HrrrLiveIngestTest` exercises the whole GRIB2 path — index, byte-range fetch,
decode, sample — against the live bucket. Enable with
`FOLIAGE_HRRR_LIVE_TEST=true`; it skips otherwise, because it needs the public
internet and a product still inside the rolling window.

A committed fixture was rejected: one GRIB2 message is 1.2 MB, more than this
repository should carry for a single test. The offline coverage above tests
the parsing, which is where the bugs actually live.

Its assertions are deliberately loose on values and strict on structure —
Miami must out-warm Vermont (a transposed grid index would fail that), and a
point outside CONUS must return null rather than a clamped edge cell.

### Terrain tiles against the services they replaced

`TerrainTileLiveTest` and `CanopyTileLiveTest` cover the bulk-raster sources
(ADR-0007). Enable both with `FOLIAGE_TERRAIN_LIVE_TEST=true`. Fixtures were
rejected for the same reason as GRIB2: a canopy tile is 13 MB.

**These tests assert equivalence, not plausibility.** The claim behind the swap
is not that the tile sources return numbers — it is that they return *the same
numbers* as the point services, so replacing one with the other does not
silently redraw the forest mask or move a forecast date. So both sources are
run against the same coordinates and compared to each other, rather than the
new one being checked against hardcoded values it could drift from together
with the service:

- Canopy tiles against `getSamples`: same values within a pixel, and the
  **same forest-mask decision** at every point — including a pair straddling a
  tile edge, which is where a misaligned pixel mapping would show up first.
- Terrarium against 3DEP over 60 spread Vermont points: **bias 8.0 m, median
  7 m, p90 22 m**. Held to a bound on *bias* rather than on any single point,
  because a constant offset would shift a whole state's timing while scatter
  at summits averages out across 649 cells.

Landmark assertions exist too, but with honest tolerances: a ~300 m pixel
averages the ground around a peak, so Mount Mansfield reads ~80 m low. Valley
and lakeshore points, which is what most cells actually are, are held tight.
Death Valley gets its own assertion on *sign* — a dropped `-32768` offset would
put the entire country 32 km in the air, and a tolerance wide enough for
summits could absorb it.

The offline halves (`TerrariumTest`, `RasterGridTest`) carry the tiling and
decoding arithmetic and always run. Their failure modes are the silent ones: an
inverted latitude axis mirrors every tile while leaving mean canopy unchanged,
so each is asserted directly.


Tests that need a real database run against a real MySQL schema, and **skip
themselves when none is configured** rather than failing. Enable by setting
`FOLIAGE_TEST_DB_URL`, `FOLIAGE_TEST_DB_USER`, `FOLIAGE_TEST_DB_PASSWORD`.

These cover migrations applying cleanly, batch upserts being genuinely
idempotent, and the audit table recording partial progress on failure.

Point them at a **separate schema** (e.g. `foliage_test`), never the
development database — they truncate tables.

## What is deliberately not tested

- **Forecast accuracy.** There is no ground truth for when foliage actually
  peaked, so the model can be tested for internal consistency but not for
  correctness. This is a stated limitation of the project, not an oversight.
- **Third-party availability.** Tests do not assert that Open-Meteo or the
  NLCD service are up.

## Running

```bash
cd backend && ./gradlew test
```
