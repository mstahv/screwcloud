-- A sensor the reader has asked not to see: the neighbour's tag that drifts
-- onto the dashboard, a probe that was moved indoors for the winter. Ignoring
-- hides the card; the measurements keep arriving and keep being stored, so
-- restoring the sensor gets its history back too. A flag on the settings row
-- rather than a table of its own, because it is a setting.

ALTER TABLE sensor_settings ADD COLUMN IF NOT EXISTS ignored BOOLEAN NOT NULL DEFAULT FALSE;
