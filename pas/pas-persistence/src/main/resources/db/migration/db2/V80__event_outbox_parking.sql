-- AB-528: dead-letter parking and replay for PASAPP.EVENT_OUTBOX (ADR 0001 D4). Same columns as
-- db/migration/postgresql/V80__event_outbox_parking.sql. Applied by the DBA team through change
-- management; the table may need a REORG after ADD COLUMN (advisory REORG-pending, AREO*). No new
-- grants: the service and relay account already holds UPDATE on the table (PLT-02).

ALTER TABLE PASAPP.EVENT_OUTBOX ADD COLUMN PARKED_AT TIMESTAMP;
ALTER TABLE PASAPP.EVENT_OUTBOX ADD COLUMN REPLAY_COUNT INTEGER NOT NULL WITH DEFAULT 0;
ALTER TABLE PASAPP.EVENT_OUTBOX ADD CONSTRAINT CK_EVENT_OUTBOX_RPL CHECK (REPLAY_COUNT >= 0);

CREATE INDEX PASAPP.IX_EVENT_OUTBOX_PRK
    ON PASAPP.EVENT_OUTBOX (PARKED_AT, MESSAGE_KEY);
