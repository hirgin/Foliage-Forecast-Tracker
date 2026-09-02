# The foliage model

How this site decides what colour a hexagon should be, written for a human.

> **This is a model, not an official forecast.** No authoritative dataset
> records when a given place actually peaked, so nothing here has been
> validated against reality. What *has* been checked is that the model is
> internally consistent, and that its timing is **calibrated** so modelled
> peaks fall inside published peak windows — mean absolute error 7.1 days over
> ten reference places from Maine to Virginia to Minnesota, with no systematic
> bias left (mean signed error −1.5 days). Agreement with
> published norms is evidence of plausibility, not of accuracy. Read the
> [limitations](#what-this-model-cannot-do) before trusting a number.

> **Structure changed in `0.2.0-cdd`.** Senescence is now paced by *cooling*
> and gated by photoperiod, not accumulated from photoperiod with temperature
> as a nudge. The previous structure could not express the geography of a
> season: it put New England's peak inside a five-day window against a real
> thirty, because both its temperature terms were exactly zero in a mild
> coastal autumn. [ADR-0008](adr/0008-cooling-degree-day-senescence.md) carries
> the measurements, the failed attempt to fix it by tuning, and the
> calibration. Sections below marked *superseded* describe the old structure.

## The shape of the problem

Autumn colour is **triggered by shortening days** and **paced by temperature**.
Trees stop producing chlorophyll once photoperiod falls past a threshold; how
fast the display then develops depends on how much the weather cools. Drought,
frost and cloud cover modulate how vivid and how long-lived it is.

The model takes that division literally. Photoperiod is a **gate**: nothing
accumulates while days are still long, so a cold August banks no progress.
Once the gate opens, each day contributes **cooling degree days** — how far the
day's mean temperature sits below 20 °C — and a stand is at peak when those
reach a calibrated total.

Accumulation turns into visible colour through a saturating curve, so colour
comes on slowly at first and then holds near peak rather than racing past it.
Peak lasts **7 to 10 days** depending on the place, and near-peak or better
runs 13 to 18 — a mild coast holds longer than a cold interior, because leaf
drop is driven by frost and wind and those arrive sooner in the north.

That is the whole reason a warm coast turns later than a cold interior. A cell
averaging 10 °C accumulates about twice as fast as one averaging 16 °C, and
turns correspondingly earlier. The structure this replaced accumulated
photoperiod instead, and photoperiod barely varies across a region — so every
cell reached peak at nearly the same date whatever its weather.

### Why not a chilling threshold

The old structure counted nights below 7 °C. That is a *dormancy* threshold
borrowed from spring phenology, and a mild coastal autumn never reaches it —
so temperature contributed exactly nothing precisely where the model was most
wrong. Cooling from 20 °C downward is the autumn quantity, and every autumn day
has some of it.

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

### Recalibrated in `0.2.1`, after the ingest moved underneath it

A fitted constant is coupled to the data it was fitted against, and this is
what that costs when you forget it.

`S_PEAK` — the cooling total at which a stand is at peak — was fitted at 185
against normals built from **five years** of archive sampled at **res 5**. The
weather pipeline later moved to **three years** sampled at **res 4**, a
deliberate trade that cut a month off the national load. That changes every
cooling total in the model, so 185 no longer meant what it was calibrated to
mean. Nothing failed. Every unit test stayed green, because they all compared
peak against `S_PEAK` symbolically and so passed for *any* value of it.

What it looked like from outside was a modelling failure: the whole country ran
6 to 9 days late, and the Upper Midwest 15 to 19. It was a stale constant.

Refitted against ten reference places spanning the country:

Measured end to end, by rescoring the country and reading peak dates back out
of the API — not from the offline fit, which came out a day optimistic because
it left out the lapse-rate downscale and the drought term that real scoring
applies:

| | Before (185 / shape 1.5) | After (100 / shape 1.0) |
|---|---|---|
| Mean absolute error | 9.9 days | **7.1 days** |
| Mean *signed* error | **+8.7 days — late everywhere** | **−1.5 days** |
| Peak lasts | 7.1 days | 7.0 days |

The signed figure is the one that matters. The refit did not merely reduce
error, it removed a systematic bias: every reference place used to run late,
and now they scatter either side of their published window. What is left is
scatter, and it has a name — species.

**Shape had to move with it.** The peak band is a fixed *fraction* of `S_PEAK`
wide — 0.35 of it at shape 1.5 — so cutting the constant also halved how long
peak lasts, to 4.9 days. Timing should not be bought with duration. Shape 1.0
widens the band to 0.53 of `S_PEAK`, restoring peak to 7.1 days and lengthening
the season, while mean absolute error stays at 6.0 against 5.9 for the
nominally best fit. It is also the more conventional model: a plain exponential
approach to fully turned is senescence as first-order decay of the chlorophyll
still left.

What is given up is the slow start that a shape above 1 provided. Nothing real
is lost — the photoperiod gate already says nothing happens before the trigger,
and after it the *weather* supplies the gradual onset, because mid-September
days sit near the 20 °C base and accumulate almost nothing. Onset is gradual
because early autumn is warm, which is the actual reason.

**The tests learned two lessons.** They now assert against the *calendar*, not
against `S_PEAK`, so a constant drifting away from its data fails the build
instead of passing silently. And the structural tests — season length, peak
duration, cold-versus-mild separation — were moved off a fixture that held one
temperature from September to November. A flat autumn accumulates cooling at a
constant rate and compresses the season into a fortnight; on it the model
scores 16 days and 5 at peak, against 27 and 7.1 on real cells. Those tests
were describing the fixture rather than the model. The replacement fixture
declines from 20 °C to 2 °C, which accumulates 680 cooling degree days against
686 measured at the real Stowe cell.

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
- **The north-to-south gradient is still shallower than reality.** Measured
  across 4,694 cells it is −3.40 days per degree of latitude, against roughly
  −4.7 implied by published windows. The season is now 44 days wide against a
  real ~45, and every state median falls inside its published window, but the
  extremes are still compressed: the far north runs about a week late and
  inland Connecticut about six days early.

  Before `0.2.0-cdd` the gradient was −1.40 and the season 21 days wide. See
  [ADR-0008](adr/0008-cooling-degree-day-senescence.md).

## Measured against real leaves

Until now every check in this project was the model marking its own homework:
bounded outputs, monotonic response to each driver, peak landing where the
fitted constant said it should. All of that can be true of a model that is
confidently wrong.

46,424 USA-NPN "Colored leaves" observations across VT, NH, ME, MA and NY are
the first outside check. The headline signed error is **+31.4 points**, and
8,820 sugar and red maple observations — the stand this model actually claims
to represent — give **+27.2**. Restricting to the right species barely moved
it, which rules out the comfortable explanation.

**But that number must not be optimised against, and this is the main finding.**
Modelled progression describes a 3 km stand; an NPN record describes one plant.
Individual plants reach the top intensity bucket, but a stand's plants are never
all there at once, so the *mean* observation flattens near 75 late in the season
while progression climbs to 100 by construction. In Vermont, observations sat at
72.2 in late September against a modelled 73.3 — agreement — and the apparent
error grew afterwards purely because one curve saturates and the other plateaus.

Three separate least-squares fits against that error were run, and all three
"improved" the model the same way: by pushing peak into late October.

| Fit | rms | Mean peak, NH + ME |
|---|---|---|
| Current (100 / shape 1.0) | 23.6 | 3 Oct |
| Best unconstrained, pooled | 5.8 | **10 Nov** |
| Best unconstrained, NH + ME | 7.0 | **28 Oct** |
| Best with peak held to published windows | 9.1 | 14 Oct — *on the constraint boundary* |

New England does not peak on 10 November. Each fit was caught only by checking
peak dates against published windows, which the error metric cannot see. A fit
that sits on its constraint boundary is one that would keep going.

**Shape cannot rescue it either.** Because `SCALE` is derived, shape moves the
curve without moving peak, so it was swept alone. It fixes late September —
60.6 modelled against 29.1 observed at shape 1.0, versus 31.2 at shape 2.9 —
but October gets worse, the residual floors at rms 17.2, and the peak band
collapses from 53% of `S_PEAK` to 18%, which is peak lasting two days.

**So no constant changed.** The evidence does not support one, and every
apparent improvement was the fit absorbing a scale mismatch by breaking timing.

What replaced it is a metric the mismatch cannot fool. Spearman rank
correlation asks only whether the model *orders* the season correctly, so a
constant offset or a compressed top scores perfectly:

| | Spearman |
|---|---|
| Sugar + red maple | **0.55** |
| All species | 0.45 |

Moderate, positive, and higher for the species the model represents — which is
the expected direction. Against individual plants, where a maple in a front
garden genuinely turns ahead of the woods behind it, perfect correlation is not
achievable; the gap between 0.55 and 0.45 is the species term, unmeasured
before and now worth about 0.1 of rank agreement.

**What this changed about the September curve.** The one real defect the
observations exposed is that `SHAPE = 1.0` climbs too early: it says 61% of the
canopy has turned in late September when maples are at 29%. That is a genuine
error, it is mine, and it is recorded here rather than patched, because every
available patch costs either peak timing or peak duration. Fixing it properly
needs the curve to stop being a single global shape — which is the species
work, not another constant.

- **Species composition is the largest identifiable residual**, and after the
  `0.2.1` recalibration it is essentially the *only* structured one left. The
  remaining errors line up by forest type rather than by geography or weather,
  and they point in both directions at once:

  | Place | Error | Forest |
  |---|---|---|
  | Ely, MN | +12 days late | aspen–birch |
  | Duluth, MN | +9 late | aspen–birch |
  | Marquette, MI | +7 late | aspen–birch |
  | Litchfield, CT | −19 early | oak–hickory |

  Aspen and birch turn well before the maple–beech this model implicitly
  assumes, and oak well after. Weather cannot explain a split that tracks
  species this cleanly — the Upper Midwest sites were checked for a Great Lakes
  warm bias first, and their normals are sound: lakeside Duluth and Marquette
  run 1.6 °C above inland Ely, which is genuinely how those places differ.
  Species is the next driver worth adding, and it is worth roughly a week.

- **It does not model cloud cover or wind.** Both affect how a display is
  actually experienced, and a windstorm can end a season overnight.
- **Beyond 16 days it is climatology.** Not a forecast. A weak claim about a
  typical year, not a statement about this one.
