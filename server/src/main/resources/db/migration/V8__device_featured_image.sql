-- The device's icon grew into a featured image: a picture in the card's media
-- slot instead of a small line drawing. The column holds a URL — for the
-- bundled public-domain paintings an application-relative path, for the
-- user's own picture whatever address they gave. Null means none.
--
-- The icon column goes with the drawings it named; it never shipped past a
-- development database.

ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS image_url VARCHAR(512);
ALTER TABLE device_settings DROP COLUMN IF EXISTS icon;
