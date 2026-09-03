# ADR-0009: Forest type as a model term

**Status:** accepted — CONUS surveyed, term live behind a baseline fallback

## Context

[ADR-0008](0008-cooling-degree-day-senescence.md) left one residual it could
not explain. After the `0.2.1` recalibration removed the model's systematic
lateness, the errors that remained did not line up with geography or weather.
They lined up with trees:

| Place | Error | Forest |
|---|---|---|
| Ely, MN | +12 days late | aspen–birch |
| Duluth, MN | +9 late | aspen–birch |
| Marquette, MI | +7 late | aspen–birch |
| Litchfield, CT | −19 early | oak–hickory |

The Upper Midwest normals were checked for a Great Lakes warm bias first and
are sound — lakeside Duluth and Marquette really do run 1.6 °C above inland
Ely. Weather cannot explain a split that tracks species this cleanly.

Two independent measurements then arrived at the same place within a day.
Validating against 46,424 USA-NPN leaf-colour observations scored **0.55 rank
agreement for maples against 0.45 for all species** — the model doing better on
the trees it claims to describe, which is the expected direction and the size of
the gap is the species effect. Separately, the September over-climb turned out
to be unfixable by any single curve shape, because no one shape suits maple,
aspen and oak at once.

The model had no representation of species at all. It described a maple–beech
stand everywhere.

## Decision

**Store an FIA forest type group per cell, and scale `S_PEAK` by it.**

### Where the data comes from

USFS BIGMAP `ForestTypeGroup_2018`, CONUS at 30 m, on the same ArcGIS host the
canopy source already reads. It reuses `RasterGrid` and `CellSampling`
unchanged and inherits both traps [ADR-0007](0007-bulk-rasters-not-point-sampling.md)
documents — tiff rather than PNG, and native resolution rather than a pyramid
overview. Both were confirmed here before anything was written: point queries
without a pixel size returned 0 almost everywhere, including unambiguous forest.

The Forest Atlas layers of the same name are 250 m and 20 km and are not this.

### Why a multiplier on the threshold, not an offset in days

A fixed day offset would be wrong everywhere except where it was fitted. The
same shift in days costs very different amounts of cooling in Minnesota and
Georgia, and the point of a degree-day model is that timing follows accumulation
rather than the calendar. Scaling the threshold keeps that property: an aspen
stand needs *less cooling* to turn, wherever it stands.

### Why the values are measured

Each multiplier is the ratio of accumulated cooling at the date a place actually
peaks to the accumulation where the model peaks it, read from the same weather
the model scores:

| Group | Measured | Shipped |
|---|---|---|
| Aspen–birch | 0.57, 0.59, 0.67 | **0.61** |
| Maple–beech–birch | 1.00 | **1.00**, baseline by definition |
| Oak | 2.61 at one town | **1.6**, damped |
| Everything else | — | 1.0 |

### Why a null cell scores exactly as before

141,274 cells cannot be sampled in one job, so "not sampled yet" has to be an
ordinary state rather than a failure. An unsampled cell falls back to the
maple–beech baseline and scores bit-for-bit as it did before this existed. That
is what let the term ship against a partly surveyed grid, and it makes the
term's effect attributable: at any moment the difference between a sampled and
an unsampled cell is the term itself.

## Consequences

**The model was built for 3% of the map.** The national survey — all 217,412
cells — puts maple–beech–birch, the stand every constant was fitted against, at
3.0% of the grid. Oak is 22.4%, seven times more widespread. Conifer is 30.4%
and 39.8% carries no continuous forest. That was invisible while the evidence
was ten reference towns, six of them in New England.

**One global oak constant cannot be right.** The obvious next move looked like
measuring oak properly, since it ships damped at 1.6 against 2.61. Testing what
those values do says otherwise: at 1.6 the northern oak sites run slightly early
and the southern ones already run late, and at 2.61 Virginia lands about right
while the Ozarks never peak inside the season at all. The residual is the
north–south compression already recorded in `docs/model.md`, not a mismeasured
species value. Fixing it means a latitude interaction, which is a larger piece
of work than a constant.

**The per-cell mode discards mixture.** A cell votes on its seven sample points
and takes the winner, so Ely — aspen–birch country — classified as maple–beech
because its points landed that way, and the term does not reach the very place
whose error motivated it. Averaging *multipliers* across sample points instead
of voting on *codes* would fix this and is legitimate where averaging codes is
not, since multipliers are continuous. It needs a re-survey to take effect.

**The raster returns types, not only groups.** The national survey came back
holding 841, 402, 128 and some two hundred others — individual FIA forest types,
which FIA nests inside groups. Matching group codes alone left 8.5% of the grid
reading as unsurveyed, including 14,471 cells of pinyon–juniper whose group code
was simply missing from the list. Codes now resolve to the highest group at or
below them. Neither of the two states surveyed first could have exposed this;
neither has any pinyon–juniper.

## Alternatives considered

**Leave species out and widen the error bars.** Defensible while the residual
was four towns. Not defensible once 46,424 observations put a number on it and
the survey showed the baseline covers 3% of the country.

**Fit a per-species curve shape rather than a threshold multiplier.** More
faithful — species differ in how fast they turn, not only when — but it needs
per-species observations dense enough to fit a shape, and NPN's intensity
buckets are too coarse for that. A threshold is one measurable number per group.

**Use the 250 m Forest Atlas layer.** Coarser than the cells it would describe.
At 250 m a res 6 hexagon is a handful of pixels, which reintroduces exactly the
noise `CellSampling` exists to average out.
