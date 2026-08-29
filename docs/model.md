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

> **Climatological chilling is averaged as a derived quantity, not derived
> from averaged temperature.** Chilling is a threshold function, and the mean
> of a nonlinear function is not the function of the mean. At a southern
> Vermont cell, individual years had 2–10 nights below 7 °C between 7 September
> and 8 October, but the five-year *mean* series had only 2 — cold snaps land
> on different dates each year and average away. Before this was fixed, every
> climatological day, which is most of the season, scored on photoperiod
> alone. Chilling is now computed per year and then averaged.


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

Both sides of that comparison must cover the **same window**. The weather
series reaches back well before the season so chilling can accumulate, and
summing all of it against a season-length normal made every cell look soaked —
which silently disabled the drought term entirely. Accumulation now starts at
the season boundary on both sides.

Note that for a purely climatological date the observed series *is* the normal,
so the anomaly is zero by construction. That is correct: a future drought
cannot be known, only a current one measured.

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
| `CHILL_DIRECT` | 0.55 | Chilling's own contribution, independent of daylength |
| `SENESCENCE_RATE` | 0.0402 | When peak lands |

`CHILL_GAIN` came first because it controls *spread*. At its original 0.09 the
model had the right direction — elevation-to-progression correlation of 0.79 —
but far too small a magnitude: 954 m of elevation moved peak by about two days,
against a field rule of thumb of roughly a week per 300 m. Raising it to 0.30
widened the statewide spread from 5 days to 9.

`SENESCENCE_RATE` was then solved for directly by inverting the curve at the
median cell.

The resulting season, computed over all 649 Vermont cells:

| | |
|---|---|
| Earliest peak | **23 September** — the high Green Mountains |
| Median peak | **8 October** |
| Latest peak | **15 October** |
| Statewide spread | 22 days |

On 7 October: 390 cells at peak, 242 near peak, 17 already past.

Elevation-to-progression correlation is **0.78**, so the lapse-rate downscale
is doing real work — the earliest cells to turn are the high ones, which is
where a Vermont season actually starts.

These figures are from after the climatological-chilling correction described
under [Chilling](#chilling--the-accelerator). Before it, earliest peak was
5 October and the spread 9 days, because high-elevation cells were getting no
chilling credit on climatological days.

The calibration is guarded by a test. Changing a constant without moving the
test fails the build.

## Does colour really move north to south?

Yes, but weakly within Vermont — and establishing that took three measurements,
two of which were wrong.

**Photoperiod alone favours the south.** Holding weather constant, southern
latitudes cross the 13-hour threshold about six days earlier and the north has
not caught up by early October. Pinned by a test, negative result and all.

**The data does carry a northern signal.** Ten northern against ten southern
Vermont points, spread in longitude so the mountains favour neither:

| Band | Mean elevation | Mean tmin | Accumulated chilling |
|---|---|---|---|
| North | 333 m | 8.86 °C | **47.9** |
| South | 413 m | 9.03 °C | 36.7 |

Mean temperature differs by only 0.17 °C, but chilling by **31%** — and the
north manages it while sitting 80 m *lower*. Chilling is a threshold function,
so what matters is how many nights fall below 7 °C, not the average. The same
nonlinearity that broke climatological chilling.

**The model was losing that signal.** Chilling could only *multiply* the
photoperiod term, and photoperiod favours the south, so the two cancelled.
Measured across the full grid the result was 1.0 progression point north to
south, non-monotonic — effectively nothing. Chilling now also contributes
forcing directly (`CHILL_DIRECT`), because cold nights drive senescence whether
or not daylength is changing quickly.

| | Before | After |
|---|---|---|
| Peak spread across Vermont | 11 days | **22 days** |
| North minus south | 1.0 pt | **2.7 pts** |
| Monotonic south → middle → north | no | **yes** |

**It stays a modest effect here, and that is correct.** Vermont spans ~950 m of
elevation — about 6 °C by lapse rate — against 2.3° of latitude worth roughly
0.2 °C. Elevation *should* dominate inside one small state; correlations are
0.78 for elevation against 0.09 for latitude. The north-to-south march is a
continental-scale phenomenon, and Vermont is not a continent. It would show
clearly on a Maine-to-Virginia map, which is another reason expanding the grid
matters.

### Two measurement lessons

Both worth keeping, because both produced confident wrong answers.

**Sample size.** An early comparison of 5 cells per band suggested a 6.2-point
northern lead. The full grid says 1.0. Five cells is not a measurement.

**Statistic choice.** Measured as "first day reaching peak", the north-south
spread was 0.4 days, because the saturating curve is flat near the top and
cells cross the threshold within hours of each other. The same data measured as
progression showed the difference plainly.

## What this model cannot do

- **It has not been validated.** There is no ground-truth dataset of actual
  peak dates, so accuracy is unknown and unclaimed. The tests assert internal
  consistency — bounded outputs, monotonic response to each driver — and
  nothing more.
- **It does not know species.** Sugar maple, aspen, and oak turn at different
  times and in different colours. Only canopy density is currently modelled,
  not composition, so a maple stand and an oak stand at the same elevation
  score identically. This is the largest single gap.
- **It does not vary species by region.** USFS forest type data was checked:
  Vermont is almost uniformly Maple/beech/birch, with spruce/fir only at
  elevation, so species would not differentiate *within* this state. It would
  matter greatly across states — oak-dominated southern forests turn later and
  duller — which makes it a prerequisite for expanding beyond Vermont rather
  than an improvement to the current map.
- **The north-to-south gradient is far too weak, and this is now measured.**
  Across 4,694 cells in seven states it is **−1.40 days per degree of
  latitude**, against roughly −4.7 implied by published peak windows, and the
  modelled season is 21 days wide against about 45. Southern and coastal peaks
  land about ten days early.

  This is structural, not a matter of tuning. Both temperature terms are inert
  in a mild autumn — chilling needs 7 °C and warm delay needs 20 °C, and
  coastal October sits between the two — so photoperiod supplies about 80% of
  forcing and barely varies across these latitudes. Raising `WARM_DELAY`
  ninefold and `CHILL_DIRECT` by 64% widened the spread from 5 days to 8.
  [ADR-0008](adr/0008-cooling-degree-day-senescence.md) proposes the redesign
  that would fix it, and records the measurements in full.

- **It does not model cloud cover or wind.** Both affect how a display is
  actually experienced, and a windstorm can end a season overnight.
- **Beyond 16 days it is climatology.** Not a forecast. A weak claim about a
  typical year, not a statement about this one.
