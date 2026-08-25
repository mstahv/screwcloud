-- Housekeeping for the day this is not three devices in one house.
--
-- client_activity remembers when a browser token was last seen on the pages.
-- There are no accounts, so this is the only notion of "user activity" there
-- is — and it is what lets a browser that has not visited in a year be
-- forgotten: its device list, its push subscriptions, its alert choices.
--
-- Existing tokens are seeded as seen now rather than left dateless: the clock
-- on them starts today, because judging a year of absence with a table that
-- was born this morning would purge everybody.

CREATE TABLE IF NOT EXISTS client_activity (
    client_id  VARCHAR(64) PRIMARY KEY,
    last_seen  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

INSERT INTO client_activity (client_id, last_seen)
SELECT DISTINCT client_id, now() FROM client_device
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO client_activity (client_id, last_seen)
SELECT DISTINCT client_id, now() FROM push_subscription
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO client_activity (client_id, last_seen)
SELECT DISTINCT client_id, now() FROM alert_subscription
ON CONFLICT (client_id) DO NOTHING;

-- The retention sweep deletes the oldest measurements by age alone; without
-- this the nightly delete walks the whole table, because both existing
-- indexes lead with the device.
CREATE INDEX IF NOT EXISTS idx_sample_received_at
    ON measurement_sample (received_at);
