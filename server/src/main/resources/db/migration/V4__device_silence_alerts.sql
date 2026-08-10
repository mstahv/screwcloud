-- Who wants to hear that a device has stopped reporting.
--
-- A column on client_device rather than a table of its own: that table is
-- already keyed by exactly the right pair, one browser and one device, and it is
-- already the list of devices someone cares about.

ALTER TABLE client_device
    ADD COLUMN IF NOT EXISTS alert_on_silence BOOLEAN NOT NULL DEFAULT FALSE;
