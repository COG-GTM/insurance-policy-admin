-- AB-527: delivery receipts for the Claims and Actuarial feeds (ADR 0001 D6, component INT-02).
-- One row per delivered file. A row only reaches status 'CONFIRMED' after the file was renamed into
-- place on the SFTP endpoint and read back with the same SHA-256 as the staged file. Re-runs of
-- the same business date add a new row with a higher attempt, so the history stays auditable.
-- No PII: the table holds file metadata only, never record content. DB2 gets the same table in
-- PASAPP (db/migration/db2/V70__create_extract_delivery.sql).

CREATE SCHEMA IF NOT EXISTS pasapp;

CREATE TABLE pasapp.extract_delivery (
    delivery_id         bigint GENERATED ALWAYS AS IDENTITY,
    feed                varchar(32)     NOT NULL,
    business_date       date            NOT NULL,
    file_name           varchar(128)    NOT NULL,
    record_count        integer         NOT NULL,
    byte_count          bigint          NOT NULL,
    sha256              char(64)        NOT NULL,
    destination         varchar(256)    NOT NULL,
    status              varchar(16)     NOT NULL,
    attempts            smallint        NOT NULL,
    job_execution_id    bigint,
    detail              varchar(512),
    created_at          timestamp(6)    NOT NULL DEFAULT localtimestamp,
    confirmed_at        timestamp(6),
    CONSTRAINT pk_extract_delivery PRIMARY KEY (delivery_id),
    CONSTRAINT ck_extract_delivery_status CHECK (status IN ('CONFIRMED', 'FAILED')),
    CONSTRAINT ck_extract_delivery_confirmed CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);

CREATE INDEX ix_extract_delivery_feed_date ON pasapp.extract_delivery (feed, business_date);

COMMENT ON TABLE pasapp.extract_delivery IS 'AB-527 delivery receipts: one row per feed file sent over SFTP; CONFIRMED only after read-back checksum match';
