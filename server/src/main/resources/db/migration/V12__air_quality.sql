-- What a Ruuvi Air measures that a plain tag does not.
--
-- Columns rather than a value table, which is what protocol-evolution.md
-- recommended for as long as the fields stay enumerable: two nullable columns
-- cost a plain tag a bit of null bitmap, where a row per field would multiply an
-- Air's 5-minute cadence into seven rows where it now writes one — straight onto
-- the retention memo's disk arithmetic.
--
-- Nullable because most sensors have neither. That is the same shape the table
-- already had for humidity, which a RuuviTag Pro 2in1 never reports.
--
-- Nothing backfills: rows written before this knew nothing about the air, and a
-- zero would be a measurement rather than an absence.

ALTER TABLE measurement_sample ADD COLUMN IF NOT EXISTS co2 DOUBLE PRECISION;
ALTER TABLE measurement_sample ADD COLUMN IF NOT EXISTS pm25 DOUBLE PRECISION;
