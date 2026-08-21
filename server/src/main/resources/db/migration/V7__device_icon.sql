-- The device's icon: which of the line-drawn buildings stands for it in the
-- UI. A short token ("sauna", "barn"); the drawings themselves live with the
-- application, so the database only remembers the choice. Null means none.

ALTER TABLE device_settings ADD COLUMN IF NOT EXISTS icon VARCHAR(16);
