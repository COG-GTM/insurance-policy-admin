-- AB-527: delivery receipts for the Claims and Actuarial feeds on DB2 for z/OS, in schema PASAPP
-- (never ACMEINS, whose DDL in sql/ is frozen). Applied by the DBA team through change management
-- while DB2 is the system of record; PostgreSQL gets the same table in
-- db/migration/postgresql/V70__create_extract_delivery.sql. Tablespace and buffer pool are chosen
-- by the DBA team at review.

CREATE TABLE PASAPP.EXTRACT_DELIVERY (
    DELIVERY_ID         BIGINT          NOT NULL GENERATED ALWAYS AS IDENTITY,
    FEED                VARCHAR(32)     NOT NULL,
    BUSINESS_DATE       DATE            NOT NULL,
    FILE_NAME           VARCHAR(128)    NOT NULL,
    RECORD_COUNT        INTEGER         NOT NULL,
    BYTE_COUNT          BIGINT          NOT NULL,
    SHA256              CHAR(64)        NOT NULL,
    DESTINATION         VARCHAR(256)    NOT NULL,
    STATUS              VARCHAR(16)     NOT NULL,
    ATTEMPTS            SMALLINT        NOT NULL,
    JOB_EXECUTION_ID    BIGINT,
    DETAIL              VARCHAR(512),
    CREATED_AT          TIMESTAMP       NOT NULL WITH DEFAULT,
    CONFIRMED_AT        TIMESTAMP,
    CONSTRAINT PK_EXTRACT_DELIVERY PRIMARY KEY (DELIVERY_ID),
    CONSTRAINT CK_EXTRACT_DEL_STAT CHECK (STATUS IN ('CONFIRMED', 'FAILED')),
    CONSTRAINT CK_EXTRACT_DEL_CONF CHECK (STATUS <> 'CONFIRMED' OR CONFIRMED_AT IS NOT NULL)
);

CREATE UNIQUE INDEX PASAPP.IX_EXTRACT_DELIVERY
    ON PASAPP.EXTRACT_DELIVERY (DELIVERY_ID);

CREATE INDEX PASAPP.IX_EXTRACT_DEL_FEED
    ON PASAPP.EXTRACT_DELIVERY (FEED, BUSINESS_DATE);
