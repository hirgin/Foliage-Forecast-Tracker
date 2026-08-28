-- Climatological chilling, averaged as a derived quantity.
--
-- Chilling is a threshold function -- max(0, 7C - tmin) -- and the mean of a
-- nonlinear function is not the function of the mean. Averaging temperature
-- first and applying the threshold afterwards destroys the signal: at a
-- southern Vermont cell, individual years had 2-10 nights below 7C between
-- 7 Sept and 8 Oct, but the 5-year *mean* series had only 2, because cold
-- snaps land on different dates each year and average away.
--
-- The consequence was that every climatological day -- which is most of the
-- season -- scored on photoperiod alone, with no chilling contribution at all.
--
-- So chilling is computed per year and then averaged, rather than derived
-- from averaged temperatures.
ALTER TABLE weather_normal
    ADD COLUMN chill_units DECIMAL(4,2) NULL COMMENT 'Mean of max(0, 7C - tmin) across years',
    ADD COLUMN frost_frequency DECIMAL(3,2) NULL COMMENT 'Fraction of years with tmin <= 0C on this day';
