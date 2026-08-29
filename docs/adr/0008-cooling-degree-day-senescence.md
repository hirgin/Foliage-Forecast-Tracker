# ADR-0008: Pace senescence by cooling, not by photoperiod

**Status:** accepted and implemented, model version `0.2.0-cdd`. Supersedes the
forcing structure described in [`docs/model.md`](../model.md).

Measured over the seven scored states after implementation: the gradient went
from −1.40 to **−3.40 days per degree** against a target near −4.7, the season
from 21 days wide to **44** against a real ~45, and every state median landed
**inside** its published window. Mean absolute error over six reference places
is **4.2 days**, against roughly ten in the south before. Details at the end.

## Context

The map puts peak foliage across New England inside a five-day window. Reality
spans about three weeks. Someone reading the map would plan a trip to the wrong
place in the wrong week, which is the one thing this site exists to get right.

### What was measured

Across 4,694 scored cells the north-to-south gradient is **−1.40 days per
degree of latitude**. Published peak windows imply roughly **−4.7**. The
modelled season is 21 days wide; the real one is about 45.

By state median, against typical published windows:

| | Model | Published |
|---|---|---|
| Maine | 9 Oct | early Oct |
| Vermont | 9 Oct | 5–12 Oct |
| New York | 13 Oct | 10–20 Oct |
| Massachusetts | 13 Oct | 15–22 Oct |
| Connecticut | 14 Oct | 18–25 Oct |
| Rhode Island | 14 Oct | 20–28 Oct |

The north is roughly right. The south is about ten days early, and the spread
is 5 days against 19.

### Tuning was tried first, and does not work

Raising `WARM_DELAY` ninefold and `CHILL_DIRECT` by 64% widened the spread from
5 days to 8. Not close, and with clear diminishing returns.

The reason is visible in the model's own accumulated forcing at peak:

| | Peak | Forcing at peak | Chilling | Chilling's share |
|---|---|---|---|---|
| Fort Kent, ME | 7 Oct | 34.8 | 13.0 | 20.6% |
| Stowe, VT | 8 Oct | 36.1 | 13.3 | 20.3% |
| Provincetown, MA | 14 Oct | 35.2 | 0.0 | **0%** |
| Newport, RI | 15 Oct | 35.1 | 0.0 | **0%** |

Three findings follow.

**Temperature does nothing at all in the south.** Chilling is zero because
`CHILL_THRESHOLD_C` is 7 °C, which a mild coastal autumn does not reach. The
warm term is near zero for the mirror reason: by October coastal `tmax` sits
around 17 °C, below the 20 °C `WARM_THRESHOLD_C`. Both temperature terms are
inert exactly where the model is most wrong, so no weight on either can move
those cells. That is why the tuning experiment failed, and it would have failed
at any weight.

**Photoperiod supplies about 80% of forcing and barely varies.** Day length at
these latitudes differs little in early autumn, and the model's own notes
record that it slightly *favours* the south. With the dominant term nearly
constant across the region, every cell reaches the peak threshold at a similar
date by construction.

**The chilling weight is self-limiting.** More weight on chilling makes a
northern cell peak earlier, and an earlier peak has accumulated less chilling.
The feedback damps the very change being made.

### The threshold is borrowed from the wrong season

7 °C is a *chilling* threshold, the kind used for breaking winter dormancy in
spring phenology. Autumn senescence is not that. It is paced by cooling from
summer temperatures downward, and it is already underway at 15 °C. Applying a
dormancy threshold to autumn is why southern cells register no temperature
signal whatsoever.

This also explains why the flatness survives the shift to climatological
weather. 84% of the season is five-year normals, and northern and southern
normals differ by several degrees — the geographic signal is present in the
data. The model cannot read it, because at a 7 °C threshold most of that
difference lies above the threshold and is discarded.

## Decision

Replace additive photoperiod forcing with a **cooling-degree-day accumulation
gated by photoperiod** — the structure used in the autumn-senescence
literature, rather than the spring-phenology structure currently borrowed.

    rate(d) = 0                          if daylength(lat, d) > P_START
    rate(d) = 0                          if T(d) >= T_BASE
    rate(d) = (T_BASE - T(d))^X * w(d)   otherwise

    S = sum of rate(d) from season start
    progression = 100 * min(1, S / S_CRIT)

where `T(d)` is the day's mean temperature, `w(d)` is a photoperiod weight that
grows as days shorten, and `S_CRIT` is the accumulation at which a stand is
taken to be at peak.

The change of character is the point:

- **Photoperiod becomes a gate and a weight, not the engine.** It decides
  *whether* senescence is underway; temperature decides *how fast*.
- **Every autumn day contributes**, in proportion to how cold it is. A cell
  averaging 10 °C accumulates far faster than one averaging 16 °C, so a warm
  coastal cell reaches `S_CRIT` substantially later. That is the mechanism the
  present model has no way to express.
- **Warm delay stops being a separate term.** Warmth delays by producing a
  small `T_BASE − T`, not by subtracting from a photoperiod sum.

`T_BASE` should sit near the top of the autumn range — around 20 °C — so that
the whole autumn temperature distribution falls inside the accumulating region
rather than mostly above the threshold.

### What does not change

- **Frost**, **drought** and **diurnal range** keep their present roles. They
  modulate intensity and can shorten a season; they are not what sets its date.
- **Elevation** still enters through the lapse-rate downscale, and should
  strengthen: a colder ridge now accumulates faster for the same reason a
  northern cell does, rather than through a separate gain constant.
- **Explainability.** Every term stays separately reportable, which is what the
  "why" panel is for. `S / S_CRIT` is more legible than the present forcing
  sum, not less.
- **Provenance.** Cooling degree days derive from mean temperature, which
  climatological normals carry directly. No new ingest, no new quota, and no
  dependence on the `chill_units` column the current model needs.

## Measured feasibility

Step 1 of the rollout has been run against the loaded normals, before writing
any model code, to check the signal this design assumes actually exists.

**Cooling rate ranks exactly with published peak order.** Cooling degree days
below 20 °C, averaged over 1 Sep – 15 Nov:

| Place | Mean T | CDD/day | Published peak |
|---|---|---|---|
| Fort Kent, ME | 10.5 °C | 9.50 | late Sep |
| Stowe, VT | 11.2 °C | 8.83 | early Oct |
| Bar Harbor, ME | 12.5 °C | 7.46 | mid Oct |
| Litchfield, CT | 13.1 °C | 6.95 | mid–late Oct |
| Provincetown, MA | 15.5 °C | 4.50 | late Oct/Nov |
| Newport, RI | 15.9 °C | 4.27 | late Oct |

A 2.2x north-to-south difference in cooling rate, with no rank inversions. This
is the signal the current model discards above its 7 °C threshold.

**One fitted parameter reproduces the season.** Accumulating to a fixed `S_CRIT`
with `X = 1` and no photoperiod weight — the simplest possible form of this
design:

| | Current model | CDD, `T_BASE` 20, `S_CRIT` 185 | Published |
|---|---|---|---|
| Spread across the six | 5 days | **28 days** | 30 days |
| Mean absolute error | ~10 days in the south | **3.8 days** | — |

Per place, with a residual sign:

    Fort Kent       3 Oct   (+5)
    Stowe           9 Oct   (+1)
    Bar Harbor     10 Oct   (-5)
    Litchfield     15 Oct   (-6)
    Provincetown   28 Oct    (0)
    Newport        31 Oct   (+6)

`T_BASE` of 18 fits identically (3.83 days, at `S_CRIT` 100); 20 is kept as the
more conventional base. The spread is 26–29 days at *every* `S_CRIT` tried,
which is the important part: it comes from the cooling-rate ratio, not from the
fit.

**It is not overfitting.** Leave-one-out, refitting `S_CRIT` without each place
and then scoring it, moves the parameter only between 175 and 185 and gives a
mean absolute error of 4.5 days:

    Fort Kent +5, Stowe +1, Bar Harbor -6, Litchfield -7,
    Provincetown -2, Newport +6

**The residuals point somewhere specific.** Bar Harbor and Litchfield come out
early, Newport late. Litchfield is in oak-dominated country, and oak turns later
and duller than the maple/beech/birch this model implicitly assumes. Species
composition is already recorded in `model.md` as a missing driver; these
residuals are consistent with that being the next one worth adding, rather than
with the cooling structure being wrong.

The gate is passed: build it.

## Consequences

### This changes what the project claims about itself

`docs/model.md` and ADR-0004 state that every constant is a stated assumption
rather than a fitted parameter, and that accuracy against reality is explicitly
not claimed. `S_CRIT`, `T_BASE` and `X` cannot be chosen that way — they have to
be calibrated so modelled peaks land inside published windows.

That is a real change of stance, from *transparent and unvalidated* to
*transparent and calibrated against published norms*, and it must be written
into `model.md` rather than left implied. The honest framing afterwards is that
the model reproduces typical published timing, not that it predicts this year.

### Calibration has to be done carefully

Seven reference towns is a small target and easy to overfit with three free
parameters.

- Choose `T_BASE` and `X` from physical reasoning and the shape of the
  temperature distribution, not from peak dates.
- Fit only `S_CRIT` against reference windows, since it sets absolute timing
  while the others set spread.
- Hold reference places out of the fit and check them afterwards.
- Report the residual per reference place, and publish it. A model that says
  where it is wrong is worth more here than one that hides it.

### Known limits this does not fix

- **84% of the season is climatology.** The output is a typical year, not this
  year, until the observed window widens. A better model does not change that,
  and the UI should keep saying so.
- **There is still no ground truth.** Published peak windows are secondary
  sources and are themselves broad. Agreement with them is evidence of
  plausibility, not of accuracy.

### Rollout

1. **Measure first.** Compute cooling-degree-day rates from the loaded normals
   for the reference cells and confirm the north-south ratio is what this
   argument assumes, *before* writing any model code. If the ratio is not
   there, the redesign fails for a different reason and should stop here.
2. Implement behind a model version, keeping the present model callable so both
   can be scored on identical inputs.
3. Compare on the measurements already established: gradient in days per degree,
   season width, per-state medians against published windows, and
   nearest-neighbour spatial coherence.
4. Re-score, export, and only then replace.

Tests should assert what can be known: bounded output, monotonic response to
each driver, a colder cell never peaking later than a warmer one at equal
latitude, and the measured gradient falling inside a stated range.

## Sources

- Delpierre et al. (2009), *Modelling interannual and spatial variability of
  leaf senescence for three deciduous tree species in France* — the
  cooling-degree-day family this adopts. Read the parameterisation from the
  source rather than from this summary.
- [Chilling, photoperiod and forcing temperature in woody plant phenology (PMC)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7176907/)
- [The science behind peak fall colors — Scientific American](https://www.scientificamerican.com/article/the-science-behind-peak-fall-colors-what-to-expect-in-2025/)
- Present behaviour and the measurements above: [`docs/model.md`](../model.md), ADR-0005.

## Outcome

Implemented as `CoolingDegreeDayModel`, selected by `foliage.model.kind`, with
the previous model kept callable so both can be scored over identical inputs.

| | Photoperiod model | Cooling model | Target |
|---|---|---|---|
| Gradient | −1.40 d/deg | **−3.40 d/deg** | ~−4.7 |
| Season width | 21 days | **44 days** | ~45 |
| Mean error, 6 reference places | ~10 days in the south | **4.2 days** | — |

State medians, all now inside their published windows:

| | Model | Published |
|---|---|---|
| Vermont | 9 Oct | 5–12 Oct |
| Maine | 10 Oct | early Oct |
| New Hampshire | 11 Oct | 8–15 Oct |
| New York | 17 Oct | 10–20 Oct |
| Massachusetts | 19 Oct | 15–22 Oct |
| Connecticut | 21 Oct | 18–25 Oct |
| Rhode Island | 24 Oct | 20–28 Oct |

Per reference place: Fort Kent +8, Stowe +2, Bar Harbor −3, Litchfield −6,
Provincetown −1, Newport +5.

Two things worth recording honestly.

**The gradient is better but not all the way there**, −3.40 against −4.7. Part
of that is real: the fit is against latitude alone, and coastal and inland
cells at the same latitude now legitimately differ by days, which is the
behaviour that was missing and which adds scatter to a latitude-only fit.

**Spatial coherence loosened**, from a mean 0.15-day gap between neighbouring
coastal cells to 0.88. That is not the old bimodal defect returning — it is the
model expressing elevation and maritime differences it previously flattened.
Worth watching, not worth reverting: 0.15 was the signature of a model that
said the same thing everywhere.

The residual pattern still points at species composition — Litchfield, in oak
country, remains the largest negative residual, and oak turns later than the
maple/beech/birch this assumes. That remains the next driver worth adding.
