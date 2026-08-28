-- State name alongside FIPS.
--
-- The bootstrap works in state names because that is what the Census boundary
-- service queries on; it only learns the FIPS code after fetching the outline,
-- which is the expensive step a resumable run wants to skip. Storing the name
-- lets "has this state already been done?" be answered without a network call.
--
-- Three separate statements, not one combined ALTER. TiDB does not apply a
-- multi-clause ALTER as a single schema change, so an ADD INDEX in the same
-- statement as its ADD COLUMN fails with "column does not exist".
-- IF NOT EXISTS keeps this re-runnable after that partial failure.
ALTER TABLE cell ADD COLUMN IF NOT EXISTS state_name VARCHAR(32) NULL;

CREATE INDEX IF NOT EXISTS cell_state_name_idx ON cell (state_name);

-- Backfill the only state loaded before this column existed.
UPDATE cell SET state_name = 'Vermont' WHERE state_fips = '50' AND state_name IS NULL;
