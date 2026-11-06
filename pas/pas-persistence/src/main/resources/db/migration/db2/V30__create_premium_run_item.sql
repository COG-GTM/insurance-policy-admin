-- AB-523: per-policy outcome of a premium recalculation run on DB2 for z/OS, in schema PASAPP
-- (never ACMEINS, whose DDL in sql/ is frozen). Applied by the DBA team through change management
-- while DB2 is the system of record; PostgreSQL gets the same table in
-- db/migration/postgresql/V30__create_premium_run_item.sql. Tablespace and buffer pool are chosen
-- by the DBA team at review.

CREATE TABLE PASAPP.PREMIUM_RUN_ITEM (
    JOB_INSTANCE_ID     BIGINT          NOT NULL,
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    BUSINESS_DATE       DATE            NOT NULL,
    STATUS              CHAR(1)         NOT NULL,
    TOTAL_PREMIUM       DECIMAL(11,2),
    ERROR_CODE          VARCHAR(64),
    DETAIL              VARCHAR(256),
    RECORDED_AT         TIMESTAMP       NOT NULL WITH DEFAULT,
    CONSTRAINT PK_PREMIUM_RUN_ITEM PRIMARY KEY (JOB_INSTANCE_ID, POLICY_NUMBER),
    CONSTRAINT CK_PREM_RUN_STATUS CHECK (STATUS IN ('U', 'E')),
    CONSTRAINT CK_PREM_RUN_TOTAL CHECK (STATUS <> 'U' OR TOTAL_PREMIUM IS NOT NULL)
);

CREATE UNIQUE INDEX PASAPP.IX_PREMIUM_RUN_ITEM
    ON PASAPP.PREMIUM_RUN_ITEM (JOB_INSTANCE_ID, POLICY_NUMBER);

CREATE INDEX PASAPP.IX_PREM_RUN_DATE
    ON PASAPP.PREMIUM_RUN_ITEM (BUSINESS_DATE);
