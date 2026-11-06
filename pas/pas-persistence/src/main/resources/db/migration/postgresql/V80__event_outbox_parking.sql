-- AB-528: dead-letter parking and replay for the transactional outbox (ADR 0001 D4).
-- A row the relay could not publish after pas.events.max-attempts tries is parked: the relay skips
-- it and every later row with the same message key (policy number), so per-policy order holds
-- while other policies keep flowing. An operator replays it (or republishes a row still inside the
-- retention period) through the ops API, which clears parked_at and published_at and counts the
-- replay. DB2 gets the same columns (db/migration/db2/V80__event_outbox_parking.sql).

ALTER TABLE pasapp.event_outbox ADD COLUMN parked_at timestamp(6);
ALTER TABLE pasapp.event_outbox ADD COLUMN replay_count integer NOT NULL DEFAULT 0;
ALTER TABLE pasapp.event_outbox ADD CONSTRAINT ck_event_outbox_replays CHECK (replay_count >= 0);

CREATE INDEX ix_event_outbox_parked ON pasapp.event_outbox (parked_at, message_key);
