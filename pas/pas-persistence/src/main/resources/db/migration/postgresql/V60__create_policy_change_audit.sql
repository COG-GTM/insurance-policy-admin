-- AB-526: durable audit of renewals and endorsements (ADR 0001 D5, component DS-05). Replaces the
-- CICS TS queue ENDORSEMENT-AUDIT, which is lost on a region restart (Q-23), and records the full
-- authenticated principal instead of the 8-character UPDATED_BY (Q-518-1). Written in the same
-- transaction as the POLICIES/ENDORSEMENTS change, so a change without its audit row cannot commit.
-- Append-only: the trigger below refuses UPDATE, DELETE and TRUNCATE. No foreign key to
-- acmeins.policies, so the audit outlives any later purge of the policy. DB2 gets the same table in
-- PASAPP (db/migration/db2/V60__create_policy_change_audit.sql).

CREATE SCHEMA IF NOT EXISTS pasapp;

CREATE TABLE pasapp.policy_change_audit (
    audit_id                bigint GENERATED ALWAYS AS IDENTITY,
    policy_number           char(12)        NOT NULL,
    operation               varchar(16)     NOT NULL,
    legacy_program          char(8)         NOT NULL,
    endorsement_seq         integer,
    endorsement_type        char(3),
    principal               varchar(255)    NOT NULL,
    client_id               varchar(64),
    correlation_id          varchar(64),
    policy_status_before    char(2),
    policy_status_after     char(2),
    effective_date_before   date,
    expiry_date_before      date,
    effective_date_after    date,
    expiry_date_after       date,
    premium_before          numeric(11,2),
    premium_adjustment      numeric(11,2),
    premium_after           numeric(11,2),
    renewal_count_after     smallint,
    occurred_at             timestamp(6)    NOT NULL,
    CONSTRAINT pk_policy_change_audit PRIMARY KEY (audit_id),
    CONSTRAINT ck_pca_operation CHECK (operation IN ('RENEWAL', 'ENDORSEMENT')),
    CONSTRAINT ck_pca_endorsement CHECK (
        operation <> 'ENDORSEMENT' OR (endorsement_seq IS NOT NULL AND endorsement_type IS NOT NULL))
);

CREATE INDEX ix_pca_policy ON pasapp.policy_change_audit (policy_number, occurred_at);

CREATE FUNCTION pasapp.policy_change_audit_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'pasapp.policy_change_audit is append-only';
END
$$;

CREATE TRIGGER tr_pca_no_update_delete
    BEFORE UPDATE OR DELETE ON pasapp.policy_change_audit
    FOR EACH ROW EXECUTE FUNCTION pasapp.policy_change_audit_append_only();

CREATE TRIGGER tr_pca_no_truncate
    BEFORE TRUNCATE ON pasapp.policy_change_audit
    FOR EACH STATEMENT EXECUTE FUNCTION pasapp.policy_change_audit_append_only();

COMMENT ON TABLE pasapp.policy_change_audit IS
    'AB-526: append-only audit of renewals (POLRNW) and endorsements (POLEND); replaces TS queue ENDORSEMENT-AUDIT (Q-23)';
