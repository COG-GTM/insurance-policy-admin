-- AB-526: durable audit trail for policy changes (ADR 0001: a pas audit table replaces POLEND's
-- ENDORSEMENT-AUDIT CICS temporary-storage queue). Written in the same transaction as the change.
-- details holds business facts only (no policyholder PII); actor is the caller's JWT subject.
CREATE TABLE pas.policy_audit (
    audit_id        BIGINT        GENERATED ALWAYS AS IDENTITY,
    policy_number   CHAR(12)      NOT NULL,
    action          VARCHAR(30)   NOT NULL,
    program         VARCHAR(8)    NOT NULL,
    policy_version  BIGINT        NOT NULL,
    actor           VARCHAR(255)  NOT NULL,
    correlation_id  VARCHAR(255)  NOT NULL,
    occurred_at     TIMESTAMP(6)  NOT NULL,
    details         JSONB         NOT NULL,
    CONSTRAINT pk_policy_audit PRIMARY KEY (audit_id),
    CONSTRAINT fk_policy_audit_policy FOREIGN KEY (policy_number)
        REFERENCES pas.policies (policy_number)
);

CREATE INDEX ix_policy_audit_policy ON pas.policy_audit (policy_number, audit_id);
