# ADR-0006: NOAA GRIB2 ingest — blocked on a JVM truststore gap

**Status:** blocked locally; viable in CI

## Context

Phase 6 replaces Open-Meteo with NOAA HRRR read directly from GRIB2, which
would give weather at a native 3 km — matching H3 resolution 6 exactly and
retiring the lapse-rate downscale.

The data access was verified and works:

| | |
|---|---|
| HRRR on AWS Open Data | `s3://noaa-hrrr-bdp-pds`, no authentication |
| Full surface file | **133.5 MB** |
| `.idx` sidecar | 170 records, `record:byteOffset:date:VAR:level:type` |
| One variable by HTTP byte range | **1.19 MB** — a 112x reduction |

Fetching `TMP:2 m above ground` alone returns a valid standalone GRIB2 payload
(verified: leading magic bytes `GRIB`). So the expensive part of this phase —
avoiding a 133 MB download per hour of forecast — is solved.

## What blocks it

Decoding GRIB2 on the JVM means UCAR's NetCDF-Java (`edu.ucar:grib`,
`edu.ucar:cdm-core`), published only to Unidata's own Maven repository at
`https://artifacts.unidata.ucar.edu/all/`.

**No JVM on this machine can establish TLS to that host.** The served chain is:

```
artifacts.unidata.ucar.edu
  └─ InCommon RSA OV SSL CA 3
       └─ Sectigo Public Server Authentication Root R46
```

That Sectigo root is absent from this JDK's `cacerts`, so resolution fails with
`PKIX path building failed`. It is present in the Windows certificate store,
which is why `curl` verifies the same URL cleanly — a discrepancy that made the
failure look like a Gradle repository misconfiguration for several attempts.
It is not: a bare `HttpURLConnection` from `java` fails identically.

Maven Central is unaffected, so this is specific to that one root.

## Why the obvious workarounds were rejected

- **Import the root into the JDK truststore.** Fixes this machine and nothing
  else. CI would need the same treatment, and so would anyone cloning the repo.
- **Vendor the jars via `flatDir`.** `curl` can fetch them, but `flatDir`
  resolves no transitive dependencies, and NetCDF-Java pulls a substantial tree
  (Guava, SLF4J, JDOM2, protobuf and more). Enumerating that by hand is worse
  than the problem.
- **An older `edu.ucar` release from Maven Central.** Only 4.x is mirrored
  there, predating the current GRIB2 support.

## It is a local problem, not a project problem

Measured after the fact:

| JVM | Result |
|---|---|
| Oracle JDK 19, 21, 22, 23 (local) | `PKIX path building failed` — all four |
| Temurin 23 (GitHub runner) | **HTTP 200 — trusts the chain** |

Temurin builds `cacerts` from Mozilla's CA bundle, which carries the Sectigo
root; Oracle's bundle here does not. So the dependency is obtainable in CI
today, and the blocker is confined to this development machine.

## Decision

Defer. Open-Meteo remains the `WeatherSource`, and the seam it sits behind is
unchanged and still correct — this phase was always the thing that would prove
that seam, and it still can once the dependency is obtainable.

Reasonable ways forward, in preference order:

1. **Install Temurin locally and build against it.** One package, matches what
   CI already uses, and needs no repository-level workaround. The preferred
   fix, but it is a change to the development machine rather than to this
   project, so it is the owner's call.
2. Ship a project truststore merging the JDK's `cacerts` with the Sectigo root
   and point Gradle at it. Travels with the repository, but has to be
   generated on first checkout, which is awkward.
3. Decode GRIB2 without NetCDF-Java. The subset needed here is one packing
   template on one grid, but it is real work and easy to get subtly wrong.

## Consequence for the forecast

Weather stays at H3 resolution 5 and is downscaled to resolution 6 by lapse
rate, exactly as ADR-0002 describes. That downscale is defensible physics
rather than a stopgap, so nothing about the current forecast is invalidated —
it is simply less precise than HRRR would allow.
