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

### NOAA HRRR — *phase 6*
- `s3://noaa-hrrr-bdp-pds` (full archive), `s3://noaa-hrrr-pds` (rolling week)
- **Auth:** none — AWS Open Data.
- **Format:** GRIB2, decoded on the JVM with UCAR NetCDF-Java.
- **Native resolution:** 3 km, matching H3 resolution 6 exactly.

## Terrain and land cover

### NLCD / USFS Tree Canopy Cover (CONUS)
- <https://www.mrlc.gov/data/nlcd-all-usfs-tree-canopy-cover-conus>
- 30 m raster, public domain. Defines which hexagons are forest.
- Downloaded and processed **once**, offline, in the bootstrap job.

### Elevation
- Derived per cell from Open-Meteo's elevation endpoint during bootstrap,
  with min/mean/max across each hexagon driving the lapse-rate correction.

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
