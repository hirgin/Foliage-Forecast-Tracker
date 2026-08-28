# ADR-0003: Plain JDBC, not JPA

**Status:** accepted

## Context

The database carries roughly 14M forecast rows per season plus the weather
table behind it. Two access patterns dominate:

1. **Bulk ingest** — tens of thousands of rows per batch, written repeatedly.
2. **Wide range scans** — "every cell's score for one date", read as a packed
   payload and cached.

Neither involves object graphs, lazy loading, or entity lifecycles.

## Decision

Use Spring's plain `JdbcTemplate` with Flyway for schema. No JPA, no Hibernate.

Bulk writes go through JDBC batches with `rewriteBatchedStatements=true` on
the connection, which collapses a batch into multi-row `INSERT` statements —
roughly an order of magnitude faster than statement-per-row, and far faster
than `persist()` per entity. See ADR-0004 for why that is the bulk path.

## Consequences

- Ingest throughput is bounded by the network and the database, not by an
  ORM's flush cycle.
- Read paths return projections shaped for the wire, avoiding an entity layer
  that would be mapped straight back out again.
- Cost: SQL is written by hand and schema changes touch queries directly.
  Acceptable — the schema is small and deliberately stable.
