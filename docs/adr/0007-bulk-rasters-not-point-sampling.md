# ADR-0007: Read terrain in bulk, not point by point

**Status:** accepted — implemented, CONUS loaded on it

## Context

Terrain enrichment asks two questions of every hexagon: how high is it, and
how much of it is trees. Both were answered by posting coordinate lists to
hosted raster services and reading values back — `getSamples` on the USFS NLCD
canopy ImageServer, and the same on USGS 3DEP for elevation.

That works at state scale and does not survive the country.

Measured, not estimated:

| | Vermont (649 cells) | CONUS (223,650 cells) |
|---|---|---|
| Elevation, 3DEP point sampling | 13 batches, ~12 min | 4,473 batches, **~71 h** |
| Canopy, NLCD point sampling | 19 batches | 6,264 batches, **~10 h** |

The elevation figure is the one that mattered. 3DEP was itself a fix — it
replaced Open-Meteo, whose elevation endpoint is metered by request weight and
started returning 429 after seven batches. 3DEP is not rate-limited, but it is
slow: 57 s per 50-point batch when the points are spread out, because each
request touches many raster tiles.

Seventy-one hours is not a slow job. It is a job that cannot be run.

## Decision

**Fetch the ground, not the points.** Group cells by the raster tile they fall
in, fetch each tile once, and sample every cell inside it locally.

The two services need different mechanics for the same idea:

- **Elevation** — AWS's public Terrarium terrain tiles
  (`elevation-tiles-prod`, keyless). Height is packed into an ordinary PNG:
  `height = (R * 256 + G + B / 256) - 32768`. Zoom 9, ~300 m per pixel.
- **Canopy** — the same NLCD ImageServer already in use, through
  `exportImage` rather than `getSamples`. One-degree tiles at 3,700 px, which
  is the raster's native 30 m.

Both implement the interfaces that already existed. `CanopySource` had
predicted this exact swap in its own documentation: point sampling "is ideal at
state scale but will not scale to a full CONUS grid."

## Consequences

Measured on the Vermont rebuild:

| | Before | After |
|---|---|---|
| Elevation, 649 cells | ~12 min | **664 ms** (16 tiles) |
| Canopy, 4,543 points | 19 batches | **53 s** (10 tiles) |

Elevation is roughly a thousandfold faster, because 649 cells collapse onto 16
tiles. Canopy gains less — the tiles are 13 MB and the service renders them on
demand — and is now the bottleneck, but it is hours rather than a working week.

**Neither swap changes the data.** That was verified rather than assumed,
because both feed things that are easy to move by accident: elevation drives
the lapse-rate downscale that sets how fast colour climbs a mountain, and
canopy decides which hexagons are forest at all.

- Terrarium against 3DEP, 60 spread Vermont points: **bias 8.0 m, median 7 m,
  p90 22 m**. Eight metres is 0.05 °C of lapse rate.
- Canopy tiles against `getSamples` at the same coordinates: same values
  (0/0, 45/45, 18/18, 76/78 — the last one pixel apart at a boundary), and the
  **same forest-mask decision** at every point, including across a tile edge.

Both comparisons are opt-in live tests, not one-off spikes, so the equivalence
keeps being checked.

### Resolution was chosen by measurement, twice

**Elevation, zoom 9.** Over 1,755 Vermont points, zoom 9 differs from zoom 11
by a median of 8 m and a p90 of 23 m, with near-identical distributions. Each
zoom step quadruples the tile count, so zoom 11 would be ~60,000 tiles for
detail no 3 km hexagon can express. Narrow summits do read low — Mount
Mansfield comes back ~80 m under its published height — which is smoothing in
the tail, not bias.

**Canopy, native 30 m.** Coarser rasters were tried and rejected. Over 962
points on a 3 km lattice they agree on *mean* canopy exactly (65% at every
resolution) but disagree on the thing that matters: **5.5% of cells cross the
forest threshold at 90 m, and 12.6% at 250 m.** Sampling natively keeps the
tile source a drop-in instead of silently redrawing the map. The cost is 13 MB
per tile, streamed and discarded.

### Two traps worth recording

- **PNG canopy exports are a lie.** `format=png` returns four RGBA bands with
  a colour ramp applied; the numbers look like plausible percentages and are
  not canopy. Only `format=tiff` is the raw single band.
- **Resolution must be requested explicitly.** At 108 m per pixel,
  nearest-neighbour picks one arbitrary 30 m cell out of thirteen. Mount
  Mansfield read 0% canopy against the point service's 45% — a wrong answer
  that looks like a right one.

Both were caught only by comparing against the point service, which is the
argument for keeping that implementation around as a reference rather than
deleting it. Both old sources remain, no longer `@Primary`.

### Memory is bounded by design

Tiles are sampled and released inside the fetch rather than collected and read
afterwards. A canopy tile decodes to ~13 MB and a large state spans dozens, so
holding them to the end would be hundreds of megabytes of heap for data
finished with the moment it is read. Peak memory is `threads x tile` no matter
how much ground one call covers, which is what lets a single call bootstrap
Texas as safely as Vermont.

### What did not change

Per-state resumability stays. It was load-bearing when a run was 71 hours; at
minutes it is merely prudent, but a run that dies partway still should not redo
the states it finished.

## Sources

- [AWS Terrain Tiles on the Registry of Open Data](https://registry.opendata.aws/terrain-tiles/)
- [Terrarium elevation encoding](https://github.com/tilezen/joerd/blob/master/docs/formats.md)
- [ArcGIS ImageServer `exportImage`](https://developers.arcgis.com/rest/services-reference/enterprise/export-image/)
- [NLCD USFS Tree Canopy Cover (CONUS)](https://www.mrlc.gov/data/nlcd-all-usfs-tree-canopy-cover-conus)
