# ADR-0005: Observed, forecast, and climatology are one table

**Status:** accepted

## Context

The forecast horizon is far shorter than the thing being forecast.

Measured against Open-Meteo on 2026-08-27:

| | |
|---|---|
| Forecast horizon | **16 days** (`forecast_days=17` returns HTTP 400) |
| Past window on the forecast API | 92+ days |
| Historical archive (ERA5) | 1940–present, complete daily coverage |

The foliage season runs roughly 1 September to 15 November — about 75 days.
Vermont peaks in early October. On 27 August the forecast reaches only
12 September, which is **before the season meaningfully starts**.

So at any moment, most of the season lies beyond the forecast horizon. A
design that only ingests forecasts could never answer the question the site
exists to answer: *when will colour peak near me?*

## Decision

Model a cell-day as coming from one of three provenances, stored in **one
table** with a `kind` discriminator:

| kind | Source | Covers |
|---|---|---|
| `OBSERVED` | ERA5 archive / past days | Season start → today |
| `FORECAST` | Forecast API | Today → +16 days |
| `CLIMATOLOGY` | Multi-year archive mean for that calendar day | +16 days → season end |

The phenology model consumes a single continuous daily series per cell and
does not branch on provenance. It reads `kind` only to attach a confidence
band, because a climatological October is a much weaker claim than an
observed September.

## Consequences

- **Forecasts sharpen as the season approaches.** A cell's October estimate is
  climatology in August, and becomes real forecast data in late September.
  Re-running ingest daily upgrades `CLIMATOLOGY` rows to `FORECAST`, then to
  `OBSERVED`. Kind is part of the primary-key-adjacent state, and later kinds
  overwrite earlier ones — never the reverse.
- **Accumulated drivers stay honest.** Chilling accumulation and drought
  anomaly integrate from 1 September, so they are grounded in observed data
  even when the target date is climatological. That is precisely how published
  foliage outlooks work: current conditions plus normals.
- **The UI must show this.** A date beyond the forecast horizon is an estimate
  from normals, not a forecast, and saying so is the difference between an
  honest product and a misleading one.
- Climatology needs several years of archive per cell. That is a bulk
  historical backfill — bounded and one-off, but the largest single ingest in
  the project, and the reason ingest jobs must be resumable.

## Alternatives rejected

- **Forecast only.** Cannot answer the product's central question.
- **Climatology only.** Ignores the current year, so every year looks alike and
  drought or a warm September never shows up.
- **Separate tables per provenance.** Pushes a three-way union into every read
  path and into the model, for no gain: the columns are identical.
