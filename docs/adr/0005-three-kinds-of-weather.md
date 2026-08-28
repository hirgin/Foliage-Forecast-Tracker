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

## Amendment: normals are not the same as the fallback

**Added 2026-08-27, while wiring the model to the data.**

The decision above conflated two distinct things under "climatology":

1. **A fallback estimate** for days past the forecast horizon — deliberately
   replaced by forecast and then observed data as the season approaches.
2. **A normal**: what a given calendar day is *usually* like, used as the
   baseline for anomaly terms such as drought.

The precedence rule handles (1) correctly and is fatal to (2). Once the
forecast job had written Sept 1–12, the climatological rows for those days
were gone — so the drought term had nothing to compare the observed rainfall
against, precisely on the days where the comparison matters most.

Normals therefore live in their own table, `weather_normal`, keyed by
`(h3, month_day)` and never overwritten by daily ingest. The climatology job
writes both: normals unconditionally, and `CLIMATOLOGY` fallback rows into
`weather_daily` under the usual precedence rule.

The tell was that `weather_normal` is keyed by **calendar day** rather than
date. A normal is year-independent by definition; anything year-keyed is an
estimate of a particular day, not a baseline.
