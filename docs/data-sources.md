# Data sources

Every external input, where it comes from, and what it costs.

## Weather

### Open-Meteo — *phases 2–5*
- Forecast: <https://api.open-meteo.com/v1/forecast>
- Historical (ERA5 reanalysis, 1940–present):
  <https://archive-api.open-meteo.com/v1/archive>
- **Auth:** none. **Cost:** free under 10k calls/day non-commercial.
- **Native resolution:** ~9–25 km, which is why ingest happens at H3
  resolution 5 rather than 6. Fetching at 3 km here would invent precision.
- Escape hatch if limits bite: Open-Meteo is open source and self-hostable via
  Docker, which removes them entirely.

### NOAA HRRR — *phase 6 (see [ADR-0006](adr/0006-grib2.md))*
- `s3://noaa-hrrr-bdp-pds` (full archive), `s3://noaa-hrrr-pds` (rolling week)
- **Auth:** none — AWS Open Data.
- **Format:** GRIB2, decoded on the JVM with UCAR NetCDF-Java. **The build
  requires Temurin** — Oracle's JDK cacerts cannot reach Unidata's Maven
  repository at all. See ADR-0006.
- **Byte-range access works.** Each file has an `.idx` sidecar listing record
  offsets, so a single variable costs **1.19 MB against a 133.5 MB file**.
- **Native resolution:** 3 km, matching H3 resolution 6 exactly.

## Terrain and land cover

### Canopy — NLCD / USFS Tree Canopy Cover (CONUS)
- <https://www.mrlc.gov/data/nlcd-all-usfs-tree-canopy-cover-conus>
- Served from an ArcGIS ImageServer. 30 m raster, public domain. Defines which
  hexagons are forest.
- **The floor is 5% canopy, not 20.** It is applied at query time rather than
  at ingest, so it can be retuned without re-sampling a single tile — and it
  was. At 20 the map carried colour on 54% of the grid and nothing on the
  rest, which across the farm belt meant most of the screen had no forecast:
  Ohio scored half its cells, Iowa an eighth. Land with a scattering of trees
  still turns colour in autumn.
- The distribution says where the floor belongs. 5% scores 73.5% of the grid
  against 54.3% at 20, while going down to 1% adds only 2.7 points more —
  nearly everything with any trees sits above 5, and nearly nothing sits
  between 0 and 5. What stays uncoloured is genuinely treeless: 38% of Iowa's
  cells record zero canopy, and no threshold makes row crops turn.
- **Read as tiles via `exportImage`**, one degree at 3,700 px, which is the
  raster's native 30 m. Cells are grouped by tile so each is fetched once.

Two traps, both found only by comparing against the point service:

- **`format=png` is not data.** It returns four RGBA bands with a colour ramp
  applied. The numbers look like plausible percentages and are not canopy.
  Only `format=tiff` is the raw single band.
- **Resolution has to be asked for.** At 108 m per pixel, nearest-neighbour
  picks one arbitrary 30 m cell out of thirteen; Mount Mansfield read 0%
  canopy against the point service's 45%.

**Why native resolution and not coarser.** Measured over 962 points on a 3 km
lattice across Vermont, coarser averaged rasters agree on *mean* canopy exactly
— 65% at every resolution tried — but disagree on the decision that matters:
**5.5% of cells cross the forest threshold at 90 m, and 12.6% at 250 m.**
Sampling natively keeps the tile source a drop-in for the point service rather
than a silent redraw of the map. The cost is 13 MB per tile, streamed and
discarded.

### Elevation — AWS Terrain Tiles (Terrarium)
- <https://registry.opendata.aws/terrain-tiles/> —
  `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`
- **Auth:** none. Ordinary PNGs with height packed into the channels:
  `height = (R * 256 + G + B / 256) - 32768`. The offset is what allows
  below-sea-level terrain; Death Valley decodes negative rather than clamping.
- **Zoom 9**, ~300 m per pixel, under 4,000 tiles for CONUS.

**Why zoom 9.** Over 1,755 Vermont points, zoom 9 differs from zoom 11 by a
median of 8 m and a p90 of 23 m, with near-identical distributions. Each zoom
step quadruples the tile count, so zoom 11 would be ~60,000 tiles for detail no
3 km hexagon can express. Narrow summits do read low — Mansfield comes back
~80 m under its published height — which is smoothing in the tail, not bias.

**It matches the source it replaced.** Against 3DEP over 60 spread Vermont
points: bias 8.0 m, median 7 m, p90 22 m. Eight metres is 0.05 °C of lapse
rate, so re-running the bootstrap does not move any forecast date.

### Why both moved to bulk rasters

Point sampling worked at state scale and could not reach the country. Measured:

| | Vermont (649 cells) | CONUS (223,650 cells) |
|---|---|---|
| Elevation, 3DEP point sampling | 13 batches, ~12 min | 4,473 batches, **~71 h** |
| Canopy, NLCD point sampling | 19 batches | 6,264 batches, **~10 h** |
| Elevation, terrain tiles | **664 ms** (16 tiles) | under 4,000 tiles |
| Canopy, `exportImage` tiles | **53 s** (10 tiles) | ~1,500 tiles |

Seventy-one hours is not a slow job but an impossible one. See ADR-0007.

Canopy is now the bottleneck: its tiles are 13 MB and rendered on demand, so
it gains far less than elevation, which collapses 649 cells onto 16 tiles.

#### Previously: USGS 3DEP point sampling
- <https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer>
- Batched `getSamples`, batch size 50 — measured, not guessed: 0.36 s/point at
  50, but 0.73 s/point at both 25 and 100, and CloudFront returns 504 above
  roughly 200 spread points.

Chosen because it is **not rate-limited**, not because it is finer: across all
649 Vermont cells, 3DEP (1 m) and Open-Meteo (~90 m) agreed to min/median/max
of 30/371/967 against 27/380/981. One centroid sample per 36 km² hexagon cannot
exploit metre-scale detail.

The real rate was worse than the clean path suggested. During the New England
bootstrap: 21 batches in 20 minutes, **~57 s per batch**, because most needed a
retry after a 504. The isolated 18 s measurement assumed none, and the earlier
estimate of 7.6 hours was optimistic by roughly an order of magnitude.

Retained as a fallback and as the reference the tile source is validated
against — keeping it is what caught both `exportImage` traps above.

#### Previously: Open-Meteo elevation

**Metered by request *weight*, not count.** The first Vermont bootstrap fired 7
batches of 100 in 1.9 s and the seventh returned `429 Minutely API request
limit exceeded`. Clients retry on 429 with backoff, which recovered the batch
at a cost of ~74 s. This was the original reason for moving to 3DEP.

### Still open: sampling cells that will never be forecast

The grid stores every tiled cell so the forest threshold can be retuned without
re-sampling, which is right at state scale. Nationally that is 223,650 cells
against 76,041 forested ones — three times the terrain work for cells that will
never be forecast. Sampling canopy first and elevation only above a low canopy
floor would cut it substantially without losing the ability to retune. Less
urgent now that both sources are bulk, but the waste is real.

## Places

### GeoNames US dump
- <https://download.geonames.org/export/dump/US.zip> — 68 MB, no auth
- 2.24 million US features; 316,341 kept.

**Filtered by feature code, not population.** Foliage destinations are small or
unpopulated: Killington and Grafton record zero, Manchester 740, Woodstock 871,
Stowe 4,314. The `cities15000` tier would have listed Burlington and nothing
else anyone drives to see leaves.

Kept codes: `PPL*` (towns), `PRK` (parks), `FRST`/`RESF` (forests), `MT`/`PK`
(mountains), `GAP` (notches). Everything else is discarded — over a million of
the 2.24 million features are buildings, streams and survey marks.

Natural features are included deliberately: Mount Mansfield and Smugglers'
Notch are destinations in a way most towns are not.

Every US place is stored regardless of the current grid, since resolving one to
a hexagon needs only its coordinates. Expanding the grid lights up its towns
without reprocessing two million rows. The export publishes only places inside
the grid — a search result with no forecast behind it is worse than not listing
it.

## Phenology reference

- USA National Phenology Network — <https://www.usanpn.org/data/code>
  No API key; honour-system self-identification. Held as a **candidate
  validation source**, not currently ingested.
- NASA MODIS/VIIRS vegetation indices via AppEEARS — candidate source for a
  future ML calibration of the model. Not currently ingested.

## National outline (map presentation only)

- **us-atlas** national boundary — <https://github.com/topojson/us-atlas>,
  derived from the same Census TIGER boundaries `ConusStates` names its states
  after, so the coastline the mask draws and the coastline the hexagons stop at
  agree.

Used purely for presentation: the map covers everything that is not the United
States with a single filled polygon, because constraining the camera stops you
*travelling* to Canada but the basemap still draws it. Never read by the model
or the ingest.

Committed as `frontend/src/map/us-mask.json` rather than fetched — trimmed to
CONUS, rounded to four decimals (about 11 m, far finer than a 3 km hexagon),
72 KB. The border of the United States does not change often enough to justify
a build step, and a committed asset is one fewer thing that can fail at deploy
time.

## Licensing note

All currently ingested sources are either US federal public-domain works (NOAA,
USGS, USFS) or openly licensed (Open-Meteo, CC BY 4.0). The national outline
used for the map mask is ISC-licensed and Census-derived. Attribution appears
in the app's "How It Works" page.
