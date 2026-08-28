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

### NLCD / USFS Tree Canopy Cover (CONUS)
- <https://www.mrlc.gov/data/nlcd-all-usfs-tree-canopy-cover-conus>
- 30 m raster, public domain. Defines which hexagons are forest.
- Downloaded and processed **once**, offline, in the bootstrap job.

### Elevation — USGS 3DEP
- <https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer>
- **Auth:** none. Batched `getSamples`, same interface as the canopy service.
- **Batch size 50, measured not guessed.** 3DEP is slow when points span many
  raster tiles: 0.36 s/point at 50, but 0.73 s/point at both 25 and 100, and
  CloudFront returns 504 Gateway Timeout above roughly 200 spread points.

**Its resolution advantage is illusory at this grid scale.** Across all 649
Vermont cells, 3DEP (1 m) and Open-Meteo (~90 m) agree closely — min/median/max
of 30/371/967 against 27/380/981. One centroid sample per 36 km² hexagon cannot
exploit metre-scale detail. 3DEP is used because it is **not rate-limited**,
not because it is finer.

**Neither is right for CONUS, and the real figure is far worse than the clean
path suggests.** Measured during the New England bootstrap: 21 batches in 20
minutes, or **~57 s per batch**, because most batches need one or two retries
after a 504. The isolated measurement of 18 s assumed no retries.

At that rate a full CONUS pass is 4,473 batches and **roughly 71 hours**, and
that is before canopy sampling. Point-sampling elevation does not scale to a
national grid.

For the record, the earlier estimate of 7.6 hours was optimistic by roughly an order of magnitude.

A bulk DEM is therefore not an optimisation but a prerequisite. At ~1 km it
would be faster, entirely sufficient given the resolution comparison above,
and more correct — each cell would get a true *mean* elevation rather than a
centroid point sample, which is the right input to a lapse-rate correction.
NetCDF-Java is already on the classpath from the GRIB work and can read one.

**Also worth reconsidering: what gets sampled at all.** The grid currently
stores every tiled cell so the forest threshold can be retuned without
re-sampling, which is right at state scale. Nationally that is 223,650 cells
against 76,041 forested ones — three times the elevation work for cells that
will never be forecast. Sampling canopy first and elevation only above a low
canopy floor would cut it by two thirds without losing the ability to retune.

#### Previously: Open-Meteo elevation

**Measured rate limit.** Open-Meteo meters by request *weight*, not request
count: a batch of 100 coordinates costs far more than a single lookup. The
first Vermont bootstrap fired 7 batches of 100 in 1.9 s and the seventh came
back `429 Minutely API request limit exceeded`. Clients now retry on 429 with
exponential backoff, which recovered the batch on re-run at a cost of ~74 s.

**This does not scale to CONUS.** 224,000 cells is 2,240 batches; at the
observed throttle that is hours of mostly-waiting. Before the grid leaves the
state scale, elevation needs a bulk source (a DEM tile set processed offline)
rather than a metered API. The `ElevationSource` seam exists for exactly this
swap.

## Phenology reference

- USA National Phenology Network — <https://www.usanpn.org/data/code>
  No API key; honour-system self-identification. Held as a **candidate
  validation source**, not currently ingested.
- NASA MODIS/VIIRS vegetation indices via AppEEARS — candidate source for a
  future ML calibration of the model. Not currently ingested.

## Licensing note

All currently ingested sources are either US federal public-domain works (NOAA,
USGS, USFS) or openly licensed (Open-Meteo, CC BY 4.0). Attribution appears in
the app's "How It Works" page.
