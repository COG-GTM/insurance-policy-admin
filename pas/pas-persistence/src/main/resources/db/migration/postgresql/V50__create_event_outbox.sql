-- AB-525: transactional outbox (ADR 0001 D4, component DS-04). Events are inserted in the same
-- transaction as the business change and relayed to Kafka by the outbox relay, so a crash between
-- commit and publish can no longer lose a message (quirk Q-13). event_id is the idempotency key
-- consumers deduplicate on. Published rows are deleted by the relay after the retention period
-- (7 days). No direct PII: payloads carry policy fields and pseudonymous ids only.
-- DB2 gets the same table in PASAPP (db/migration/db2/V50__create_event_outbox.sql).

CREATE SCHEMA IF NOT EXISTS pasapp;

CREATE TABLE pasapp.event_outbox (
    outbox_seq          bigint GENERATED ALWAYS AS IDENTITY,
    event_id            char(36)        NOT NULL,
    event_type          varchar(64)     NOT NULL,
    topic               varchar(128)    NOT NULL,
    message_key         varchar(64)     NOT NULL,
    payload             varchar(16000)  NOT NULL,
    created_at          timestamp(6)    NOT NULL DEFAULT localtimestamp,
    published_at        timestamp(6),
    attempts            integer         NOT NULL DEFAULT 0,
    last_error          varchar(512),
    CONSTRAINT pk_event_outbox PRIMARY KEY (outbox_seq),
    CONSTRAINT uq_event_outbox_event UNIQUE (event_id),
    CONSTRAINT ck_event_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_event_outbox_pending ON pasapp.event_outbox (published_at, outbox_seq);

COMMENT ON TABLE pasapp.event_outbox IS 'Transactional outbox (ADR 0001 D4, DS-04); rows deleted 7 days after relay';
