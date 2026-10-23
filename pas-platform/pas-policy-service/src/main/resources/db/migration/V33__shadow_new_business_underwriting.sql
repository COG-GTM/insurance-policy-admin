CREATE SCHEMA IF NOT EXISTS pas_shadow;

CREATE TABLE pas_shadow.new_business_results (
    id BIGSERIAL PRIMARY KEY,
    request_hash CHAR(64),
    policy_type VARCHAR(3),
    coverage_types TEXT[],
    outcome VARCHAR(20) NOT NULL,
    error_fields TEXT[],
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pas_shadow.underwriting_results (
    id BIGSERIAL PRIMARY KEY,
    policy_number CHAR(12) NOT NULL,
    decision_code CHAR(2),
    risk_score INTEGER,
    decision_reasons TEXT[],
    loss_ratio NUMERIC(15,2),
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
