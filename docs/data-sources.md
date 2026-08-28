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

### Elevation
- Derived per cell from Open-Meteo's elevation endpoint during bootstrap.

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
