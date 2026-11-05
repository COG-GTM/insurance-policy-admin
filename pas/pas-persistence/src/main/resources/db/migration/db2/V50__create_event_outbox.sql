-- AB-525: transactional outbox on DB2 for z/OS, in schema PASAPP (ACMEINS DDL in sql/ is frozen).
-- Written in the same DB2 unit of work as the ACMEINS change while DB2 is the system of record
-- (ADR 0001 D4). Same shape as db/migration/postgresql/V50__create_event_outbox.sql. Applied by the
-- DBA team through change management; tablespace and buffer pool are chosen at review. Grants
-- (PLT-02): INSERT, SELECT, UPDATE, DELETE for the service and relay account.

CREATE TABLE PASAPP.EVENT_OUTBOX (
    OUTBOX_SEQ          BIGINT          NOT NULL GENERATED ALWAYS AS IDENTITY,
    EVENT_ID            CHAR(36)        NOT NULL,
    EVENT_TYPE          VARCHAR(64)     NOT NULL,
    TOPIC               VARCHAR(128)    NOT NULL,
    MESSAGE_KEY         VARCHAR(64)     NOT NULL,
    PAYLOAD             VARCHAR(16000)  NOT NULL,
    CREATED_AT          TIMESTAMP       NOT NULL WITH DEFAULT,
    PUBLISHED_AT        TIMESTAMP,
    ATTEMPTS            INTEGER         NOT NULL WITH DEFAULT 0,
    LAST_ERROR          VARCHAR(512),
    CONSTRAINT PK_EVENT_OUTBOX PRIMARY KEY (OUTBOX_SEQ),
    CONSTRAINT UQ_EVENT_OUTBOX_EVT UNIQUE (EVENT_ID),
    CONSTRAINT CK_EVENT_OUTBOX_ATT CHECK (ATTEMPTS >= 0)
);

CREATE UNIQUE INDEX PASAPP.IX_EVENT_OUTBOX
    ON PASAPP.EVENT_OUTBOX (OUTBOX_SEQ);

CREATE UNIQUE INDEX PASAPP.IX_EVENT_OUTBOX_EVT
    ON PASAPP.EVENT_OUTBOX (EVENT_ID);

CREATE INDEX PASAPP.IX_EVENT_OUTBOX_PND
    ON PASAPP.EVENT_OUTBOX (PUBLISHED_AT, OUTBOX_SEQ);
