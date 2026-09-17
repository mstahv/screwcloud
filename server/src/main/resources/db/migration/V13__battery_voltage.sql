-- The sensor's own battery, as a RuuviTag reports it in every advertisement.
--
-- A tag that is running down used to become visible only as silence, one day,
-- when its card stopped moving. The voltage has been in the advertisement all
-- along and decoded on the device for its serial log; now it travels in the
-- packet (field type 8, millivolts) and is kept here, so a falling number can be
-- seen on the card weeks before the silence.
--
-- One nullable column, like the air fields in V12 and for the same reasons: only
-- some sensors have it, and a row per field would multiply the disk arithmetic
-- in the retention memo for no gain. Stored as the reading rather than as a
-- "low" flag, so the threshold that decides "low" can move without rewriting
-- history.
--
-- Nothing backfills. Rows written before this knew nothing about batteries.

ALTER TABLE measurement_sample ADD COLUMN IF NOT EXISTS battery_voltage DOUBLE PRECISION;
