-- AB-525: append-only policy audit on DB2 for z/OS, in schema PASAPP (ADR 0001 D5, DS-05). Same
-- shape as db/migration/postgresql/V51__create_policy_audit.sql. Applied by the DBA team; the
-- service account gets INSERT and SELECT only (PLT-02), which is what makes the table append-only
-- on DB2. Replicated to PostgreSQL by the CDC pipeline (MSG-02).

CREATE TABLE PASAPP.POLICY_AUDIT (
    AUDIT_ID            BIGINT          NOT NULL GENERATED ALWAYS AS IDENTITY,
    OCCURRED_AT         TIMESTAMP       NOT NULL WITH DEFAULT,
    ACTION              VARCHAR(32)     NOT NULL,
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    PRINCIPAL           VARCHAR(256)    NOT NULL,
    CLIENT_ID           VARCHAR(64),
    LEGACY_USER         CHAR(8)         NOT NULL,
    CORRELATION_ID      VARCHAR(64),
    DETAILS             VARCHAR(8000)   NOT NULL,
    CONSTRAINT PK_POLICY_AUDIT PRIMARY KEY (AUDIT_ID)
);

CREATE UNIQUE INDEX PASAPP.IX_POLICY_AUDIT
    ON PASAPP.POLICY_AUDIT (AUDIT_ID);

CREATE INDEX PASAPP.IX_POLICY_AUDIT_POL
    ON PASAPP.POLICY_AUDIT (POLICY_NUMBER, OCCURRED_AT);
