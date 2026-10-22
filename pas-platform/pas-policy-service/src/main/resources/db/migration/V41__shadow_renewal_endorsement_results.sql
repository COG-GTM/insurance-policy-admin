-- AB-526: SHADOW route outputs for renewal and endorsement. ADR 0001: no FKs to pas, no PII.
CREATE SCHEMA IF NOT EXISTS pas_shadow;

CREATE TABLE pas_shadow.renewal_results (
    result_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_number CHAR(12) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    term_expiry_date DATE,
    outcome VARCHAR(40) NOT NULL,
    new_effective_date DATE,
    new_expiry_date DATE,
    new_premium NUMERIC(11,2),
    rate_change_pct NUMERIC(5,2),
    renewal_count INTEGER,
    open_claims INTEGER
);

CREATE INDEX ix_shadow_renewal_policy ON pas_shadow.renewal_results (policy_number, requested_at);

CREATE TABLE pas_shadow.endorsement_results (
    result_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_number CHAR(12) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    endorsement_type CHAR(3),
    effective_date DATE,
    outcome VARCHAR(40) NOT NULL,
    endorsement_seq INTEGER,
    premium_adjustment NUMERIC(11,2),
    pro_rata_factor NUMERIC(7,6),
    total_premium_after NUMERIC(11,2)
);

CREATE INDEX ix_shadow_endorsement_policy
    ON pas_shadow.endorsement_results (policy_number, requested_at);
