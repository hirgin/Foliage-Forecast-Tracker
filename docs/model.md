# The foliage model

How this site decides what colour a hexagon should be, written for a human.

> **This is a model, not an official forecast.** No authoritative dataset
> records when a given place actually peaked, so nothing here has been
> validated against reality. What *has* been checked is that the model is
> internally consistent and that its constants put peak where published norms
> put it. Read the [limitations](#what-this-model-cannot-do) before trusting a
> number.

## The shape of the problem

Autumn colour is triggered by **shortening days** and paced by **temperature**.
Trees stop producing chlorophyll as photoperiod falls; how fast the remaining
pigments show depends on how cold the nights get. Drought, frost, and cloud
cover then modulate how vivid and how long-lived the display is.

So the model accumulates a daily *forcing* from the start of the season and
compares it against the amount of forcing that corresponds to a fully turned
canopy.

## Drivers

### Photoperiod — the trigger

Day length is computed from latitude and date by the Forsythe et al. (1995)
model. Below a threshold of **13 hours**, each day contributes forcing
proportional to how far below the threshold it has fallen.

This is the only driver that is **fully deterministic**. It needs no weather
data at all, so it is known exactly for every day of the season regardless of
how far past the forecast horizon that day sits. That is what makes an October
estimate meaningful in August — see [ADR-0005](adr/0005-three-kinds-of-weather.md).

### Chilling — the accelerator

Nights below **7 °C** amplify that day's photoperiod forcing, by 30% per degree
below the threshold. Cold nights are why a sharp early-autumn cold snap brings
colour forward — and, because temperature falls with altitude, this is the
channel through which elevation does its work. See [calibration](#calibration).

### Warmth — the brake

Days above **20 °C** subtract from forcing, at 5% per degree above. A warm
September delays the season.

### Elevation — why hexagons beat counties

Weather arrives at H3 resolution 5 (~9 km), but the forecast is computed at
resolution 6 (~3 km). Each cell's temperatures are corrected to its own
elevation using the standard environmental lapse rate of **6.5 °C per 1000 m**.

This is the single strongest argument for the hexagon grid. A Vermont valley
floor at 50 m and a ridge at 1200 m differ by roughly 7.5 °C, which is one to
two weeks of difference in when colour arrives. A county average erases that
entirely.

### Drought — shortens and dulls

Season-to-date precipitation is compared against the climatological normal.
A dry season brings colour forward slightly (15% at the extreme) and
substantially reduces vividness (up to 40%).

### Frost — accelerates, then destroys

Nights at or below freezing accelerate colour. A **hard freeze below −4 °C**
strips leaves rather than colouring them, and cuts intensity to 45%.

## Outputs

**Progression**, 0–100, bucketed into stages:

| Progression | Stage |
|---|---|
| 0–10 | No change |
| 10–30 | Patchy |
| 30–55 | Partial |
| 55–75 | Near peak |
| 75–90 | **Peak** |
| 90+ | Past peak |

**Intensity**, 0–100 — how vivid the display should be, driven mainly by the
day-night temperature spread of the preceding fortnight, reduced by drought and
hard freeze. The spread stops helping above 15 °C: wide swings help colour, but
only up to a point, and not by freezing.

**Confidence**, 0–1 — the mean provenance weight of the days behind the score:
observed 1.0, forecast 0.75, climatology 0.4. A far-future date leans on
long-run averages and the UI must say so.

**Factors** — every score carries a breakdown of its named terms, so the detail
panel can explain *why* a hexagon is the colour it is. These are recomputed on
demand rather than stored; persisting them would nearly triple the forecast
table (see [ADR-0004](adr/0004-mysql.md)).

## Turning forcing into progression

Progression **saturates** rather than growing linearly with accumulated
forcing:

```
progression = 100 * (1 - exp(-SENESCENCE_RATE * forcing))
```

A linear form was tried first and rejected against real data. Cumulative
forcing accelerates through October, so a linear model that peaked on the
right date reached past-peak only **five days later**. A canopy can only turn
once; further chilling after the leaves have changed cannot keep pushing at
the same rate. The saturating form yields a peak window of eight or nine days,
which is what a real season looks like.

## Calibration

Two constants were fitted against the ingested Vermont data, in this order:

| Constant | Value | Sets |
|---|---|---|
| `CHILL_GAIN` | 0.30 | How far apart valley and ridge run |
| `SENESCENCE_RATE` | 0.0558 | When peak lands |

`CHILL_GAIN` came first because it controls *spread*. At its original 0.09 the
model had the right direction — elevation-to-progression correlation of 0.79 —
but far too small a magnitude: 954 m of elevation moved peak by about two days,
against a field rule of thumb of roughly a week per 300 m. Raising it to 0.30
widened the statewide spread from 5 days to 9.

`SENESCENCE_RATE` was then solved for directly by inverting the curve at the
median cell, so median peak lands on 8 October.

The resulting season, computed over all 649 Vermont cells:

| Date | State of the canopy |
|---|---|
| 15 Sep | Mostly no change, some patchy |
| 25 Sep | Entirely partial |
| 1 Oct | Predominantly near peak |
| **8 Oct** | **Half the state at peak** |
| 12 Oct | 547 of 649 cells at peak |
| 18 Oct | Effectively all past peak |

Elevation-to-progression correlation is **0.718**, with the highest 80 cells
averaging 73.0 against 65.6 for the lowest 80 — so the lapse-rate downscale is
doing real work.

The calibration is guarded by a test. Changing a constant without moving the
test fails the build.

## A deliberate negative result

Holding weather constant, this model progresses **slightly faster in the south**
than the north in early October. That is not a bug. Southern latitudes cross
the 13-hour threshold about six days earlier, and the north's faster rate of
decline has not yet made up the difference by 5 October.

Northern regions peak earlier in reality because they are **colder**, not
because of daylength. Latitude acts on this model through temperature. Both
behaviours are pinned by tests, the negative one included, so that nobody
later "fixes" the model into claiming something photoperiod does not support.

## What this model cannot do

- **It has not been validated.** There is no ground-truth dataset of actual
  peak dates, so accuracy is unknown and unclaimed. The tests assert internal
  consistency — bounded outputs, monotonic response to each driver — and
  nothing more.
- **It does not know species.** Sugar maple, aspen, and oak turn at different
  times and in different colours. Only canopy density is currently modelled,
  not composition, so a maple stand and an oak stand at the same elevation
  score identically. This is the largest single gap.
- **The spread is too narrow.** Vermont's real season runs two to three weeks
  from the Northeast Kingdom to the southern valleys; this model produces
  about nine days. The *direction* is right and elevation clearly drives it,
  but the magnitude is understated — most likely because species composition,
  the missing driver above, accounts for much of the real variation.
  Widening it further by inflating `CHILL_GAIN` would be fitting the model to
  an anecdote rather than to data, so it has been left honest and documented
  instead.
- **It does not model cloud cover or wind.** Both affect how a display is
  actually experienced, and a windstorm can end a season overnight.
- **Beyond 16 days it is climatology.** Not a forecast. A weak claim about a
  typical year, not a statement about this one.
