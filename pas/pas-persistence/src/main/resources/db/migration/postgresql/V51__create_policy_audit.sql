-- AB-525: append-only policy audit (ADR 0001 D5, component DS-05). One row per write through the
-- new APIs with the full authenticated principal, which the CHAR(8) audit columns of ACMEINS
-- cannot hold (quirk Q-518-1). details holds the before/after values of the changed fields as
-- JSON. AB-526 adds renewal and endorsement actions; action is free text so it needs no change.
-- Append-only is enforced here by a trigger and in production by grants (INSERT, SELECT only).
-- DB2 gets the same table in PASAPP (db/migration/db2/V51__create_policy_audit.sql).

CREATE SCHEMA IF NOT EXISTS pasapp;

CREATE TABLE pasapp.policy_audit (
    audit_id            bigint GENERATED ALWAYS AS IDENTITY,
    occurred_at         timestamp(6)    NOT NULL DEFAULT localtimestamp,
    action              varchar(32)     NOT NULL,
    policy_number       char(12)        NOT NULL,
    principal           varchar(256)    NOT NULL,
    client_id           varchar(64),
    legacy_user         char(8)         NOT NULL,
    correlation_id      varchar(64),
    details             varchar(8000)   NOT NULL,
    CONSTRAINT pk_policy_audit PRIMARY KEY (audit_id)
);

CREATE INDEX ix_policy_audit_policy ON pasapp.policy_audit (policy_number, occurred_at);

CREATE FUNCTION pasapp.policy_audit_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'pasapp.policy_audit is append-only';
END;
$$;

CREATE TRIGGER tr_policy_audit_append_only
    BEFORE UPDATE OR DELETE ON pasapp.policy_audit
    FOR EACH ROW EXECUTE FUNCTION pasapp.policy_audit_append_only();

COMMENT ON TABLE pasapp.policy_audit IS 'Append-only audit of every write with the authenticated principal (ADR 0001 D5, DS-05); retention set by Compliance (FU-09)';
