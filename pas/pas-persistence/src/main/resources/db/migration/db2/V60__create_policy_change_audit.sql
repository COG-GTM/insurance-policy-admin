-- AB-526: durable audit of renewals and endorsements on DB2 for z/OS, in schema PASAPP (never
-- ACMEINS, whose DDL in sql/ is frozen). Replaces the CICS TS queue ENDORSEMENT-AUDIT (Q-23).
-- Applied by the DBA team through change management while DB2 is the system of record; PostgreSQL
-- gets the same table in db/migration/postgresql/V60__create_policy_change_audit.sql. Append-only
-- is enforced by granting the application id INSERT and SELECT only (no UPDATE/DELETE); tablespace,
-- buffer pool and retention (FU-09) are chosen by the DBA team at review.

CREATE TABLE PASAPP.POLICY_CHANGE_AUDIT (
    AUDIT_ID                BIGINT          NOT NULL GENERATED ALWAYS AS IDENTITY,
    POLICY_NUMBER           CHAR(12)        NOT NULL,
    OPERATION               VARCHAR(16)     NOT NULL,
    LEGACY_PROGRAM          CHAR(8)         NOT NULL,
    ENDORSEMENT_SEQ         INTEGER,
    ENDORSEMENT_TYPE        CHAR(3),
    PRINCIPAL               VARCHAR(255)    NOT NULL,
    CLIENT_ID               VARCHAR(64),
    CORRELATION_ID          VARCHAR(64),
    POLICY_STATUS_BEFORE    CHAR(2),
    POLICY_STATUS_AFTER     CHAR(2),
    EFFECTIVE_DATE_BEFORE   DATE,
    EXPIRY_DATE_BEFORE      DATE,
    EFFECTIVE_DATE_AFTER    DATE,
    EXPIRY_DATE_AFTER       DATE,
    PREMIUM_BEFORE          DECIMAL(11,2),
    PREMIUM_ADJUSTMENT      DECIMAL(11,2),
    PREMIUM_AFTER           DECIMAL(11,2),
    RENEWAL_COUNT_AFTER     SMALLINT,
    OCCURRED_AT             TIMESTAMP       NOT NULL,
    CONSTRAINT PK_POLICY_CHG_AUDIT PRIMARY KEY (AUDIT_ID),
    CONSTRAINT CK_PCA_OPERATION CHECK (OPERATION IN ('RENEWAL', 'ENDORSEMENT')),
    CONSTRAINT CK_PCA_ENDORSEMENT CHECK (
        OPERATION <> 'ENDORSEMENT' OR (ENDORSEMENT_SEQ IS NOT NULL AND ENDORSEMENT_TYPE IS NOT NULL))
);

CREATE UNIQUE INDEX PASAPP.IX_POLICY_CHG_AUDIT
    ON PASAPP.POLICY_CHANGE_AUDIT (AUDIT_ID);

CREATE INDEX PASAPP.IX_PCA_POLICY
    ON PASAPP.POLICY_CHANGE_AUDIT (POLICY_NUMBER, OCCURRED_AT);
