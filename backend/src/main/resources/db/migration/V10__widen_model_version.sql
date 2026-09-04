-- model_version at 16 characters was one release from being too small.
--
-- "0.2.1-cdd" fitted; "0.3.0-photoperiod" is 17 and the insert failed outright
-- with "Data too long for column 'model_version'". The name is a label chosen
-- by a human, so sizing it to the current one was always going to break the day
-- somebody picked a more descriptive one.
--
-- 64 is not a considered limit either, but it is far enough from any plausible
-- version string that it will not be the reason a rescore fails.
ALTER TABLE foliage_forecast MODIFY COLUMN model_version VARCHAR(64) NOT NULL;
