-- Per-sensor temperature bands shown on the gauge: alert / warning / OK /
-- warning / alert, defined by four increasing limits.
--
-- All four are nullable. A sensor with no bands configured keeps the gauge's
-- stock range and colours, so this migration needs no backfill.

ALTER TABLE sensor_settings ADD COLUMN IF NOT EXISTS alert_low DOUBLE PRECISION;
ALTER TABLE sensor_settings ADD COLUMN IF NOT EXISTS ok_low DOUBLE PRECISION;
ALTER TABLE sensor_settings ADD COLUMN IF NOT EXISTS ok_high DOUBLE PRECISION;
ALTER TABLE sensor_settings ADD COLUMN IF NOT EXISTS alert_high DOUBLE PRECISION;
