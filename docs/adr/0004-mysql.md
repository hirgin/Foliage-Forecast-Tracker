# ADR-0004: MySQL

**Status:** accepted

## Context

The project needs a hosted database. ADR-0002 removed the only geospatial
requirement — the H3 index is the spatial index, so the database stores
nothing but 64-bit integers, dates, and small numerics. That leaves no
Postgres-specific requirement in the design.

Free managed MySQL is scarcer than free managed Postgres, but the most
generous free tier available is MySQL-compatible:

| Service | Free storage |
|---|---|
| TiDB Cloud Starter (MySQL-compatible) | **5 GiB** row + 5 GiB columnar |
| Aiven for MySQL | 1 GiB |
| Neon (Postgres) | 0.5 GiB |
| Supabase (Postgres) | 0.5 GiB |

Storage headroom directly determines whether the forecast table can stay a
simple row-per-cell-per-day design instead of being packed into binary
snapshots to fit.

## Decision

Use **MySQL 8** (or a MySQL-compatible service) for both development and
production. Develop directly against the hosted instance; there is no local
MySQL and no Docker in this environment.

## Storage budget

The 5 GiB ceiling is comfortable but not unlimited, and it forces one design
choice. Per season, at ~80,000 cells × ~180 days ≈ 14.4M forecast rows:

| Design | Bytes/row | Per season |
|---|---|---|
| Lean row (h3, date, progression, stage, intensity, model version) | ~80 | **~1.5 GiB** |
| Same, plus per-factor contributions stored as JSON | ~250 | ~4.4 GiB |

**Do not persist per-factor contributions.** Storing the model's explanation
alongside every row nearly triples the table and leaves no room for a second
season. The `explain` endpoint serves exactly one cell at a time, so its
breakdown is recomputed on demand from that cell's weather — a trivial amount
of work for a single cell, and it keeps the season inside ~1.5 GiB.

## Consequences

- **No `COPY`.** MySQL's fast path is either `LOAD DATA LOCAL INFILE`, which
  requires `local_infile` enabled on both ends and is commonly disabled by
  managed hosts, or JDBC batches with `rewriteBatchedStatements=true`. We use
  the latter: it needs no server-side privileges and works identically against
  a local server and a managed one.
- **Ingest crosses the network.** With no local database, bulk loading is
  bounded by upload bandwidth and round trips, not by disk. Batches must be
  large, connections reused, and jobs resumable — a dropped connection
  mid-backfill must not restart from zero.
- Connections set `connectionTimeZone=UTC`. All stored times are UTC.
- Hosted MySQL requires TLS: `sslMode=REQUIRED`.
- Timestamp columns are `DATETIME(6)`, not `TIMESTAMP` — MySQL's `TIMESTAMP`
  range ends in 2038.
- Indexed string columns are `VARCHAR`, not `TEXT`; MySQL cannot index `TEXT`
  without a prefix length.

## On TiDB specifically

TiDB is MySQL-*compatible*, not MySQL. Wire protocol and SQL match; storage
and execution are distributed. Consequences that matter here:

- **Skip manual season partitioning.** TiDB shards by key range automatically,
  so the native `RANGE` partitioning this design assumed on stock MySQL is
  redundant. On stock MySQL it stays worthwhile.
- `ENGINE = InnoDB` is accepted and ignored.
- The default port is **4000**, not 3306.
- Verify bulk-load throughput against the real target before tuning ingest
  batch sizes; distributed writes behave differently from a local InnoDB.

## Alternatives reconsidered (2026-08-27)

Revisited whether a free hosted **Postgres** would be better. It would not.

| Option | Free storage | Real Postgres? |
|---|---|---|
| CockroachDB Cloud Basic | 10 GiB + 50M RUs | Wire-compatible only |
| Aiven | 1 GiB | Yes (cut from 5 GiB, May 2025) |
| Neon | 0.5 GB | Yes |
| Supabase | 0.5 GB | Yes, auto-pauses when idle |
| Render | 1 GB | Yes, **deleted after 30 days** |
| Xata | — | Free tier **retired** in the platform pivot |

Every genuinely-Postgres free tier caps at 0.5–1 GB. A single season is
~1.5 GiB (see the storage budget above), so none of them fit without falling
back to packed binary snapshots — the very thing this ADR exists to avoid.

The only Postgres-side contender with room is CockroachDB, which is
Postgres-*compatible* rather than Postgres, exactly as TiDB is
MySQL-*compatible* rather than MySQL. It trades one distributed lookalike for
another to gain 10 GiB instead of 5 GiB — headroom the project does not need.

**Decision unchanged.** Revisit only if a season exceeds ~4 GiB, or if the
request-unit budget rather than storage turns out to be the binding limit.

## The real constraint is request units, not storage

On usage-metered serverless tiers the free allowance is 50M RUs/month, and
this pipeline writes ~14.4M forecast rows per season plus weather ingest.
Writes, not disk, are the likeliest thing to exhaust the free tier. Two rules
follow, and they are load-bearing:

- Batch aggressively; never write row-at-a-time.
- **Upsert only dates whose inputs changed.** Recomputing and rewriting the
  whole season nightly would burn the monthly allowance in days.
