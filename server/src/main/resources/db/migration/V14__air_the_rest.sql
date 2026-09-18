-- The rest of what a Ruuvi measures, now that the protocol's reserved field
-- types are in use: the pressure every tag and Air has always broadcast, and
-- the Air's VOC and NOx indexes and its light.
--
-- Ruuvi's own app shows seven numbers for an Air and the card showed four; these
-- are the missing three, plus the light the format carries and the app does not
-- list. Four nullable columns, like V12 and V13 and for the same reasons.
--
-- Nothing backfills.

ALTER TABLE measurement_sample ADD COLUMN IF NOT EXISTS pressure DOUBLE PRECISION;
ALTER TABLE measurement_sample ADD COLUMN IF NOT EXISTS voc DOUBLE PRECISION;
ALTER TABLE measurement_sample ADD COLUMN IF NOT EXISTS nox DOUBLE PRECISION;
ALTER TABLE measurement_sample ADD COLUMN IF NOT EXISTS luminosity DOUBLE PRECISION;
