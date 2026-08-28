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
| Phenology model *(phase 3)* | `forecast/` | Scoring is monotonic in each driver and bounded 0–100 |

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

## Tier 3 — Live integration tests (opt-in)

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
