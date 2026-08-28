package com.foliage.persistence

/**
 * How many rows go out per round trip on a bulk write.
 *
 * Every repository here batches, because `rewriteBatchedStatements=true` folds
 * a batch into one multi-row INSERT and that is the difference between an
 * ingest that finishes and one that does not (ADR-0003). The batch has to be
 * *bounded*, though, and originally none of them were -- they passed the whole
 * list, so the statement grew with the data.
 *
 * That works until it doesn't, and it fails without a useful error. Scoring
 * New York sent 284,392 forecast rows as a single statement and the server
 * closed the connection mid-write, surfacing as a bare
 * `EOFException: Can not read response from server`. Vermont's 47,000 rows and
 * Maine's 170,000 had gone through fine, so the ceiling only appeared once the
 * grid grew past one state.
 *
 * 5,000 keeps the per-row network cost negligible over a hosted connection
 * while leaving the rewritten statement far inside the server's packet limit,
 * whatever the size of the state.
 */
internal const val JDBC_BATCH_SIZE = 5_000
