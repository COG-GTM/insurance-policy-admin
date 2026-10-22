-- AB-526: durable audit trail (Q-15) and per-term renewal idempotency (Q-17).
CREATE TABLE pas.policy_audit (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_number CHAR(12) NOT NULL,
    action VARCHAR(20) NOT NULL,
    reference VARCHAR(40),
    actor VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    total_premium_before NUMERIC(11,2),
    total_premium_after NUMERIC(11,2),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_audit_policy FOREIGN KEY (policy_number)
        REFERENCES pas.policies (policy_number),
    CONSTRAINT ck_audit_action CHECK (action IN ('RENEWAL', 'ENDORSEMENT'))
);

CREATE INDEX ix_audit_policy ON pas.policy_audit (policy_number, occurred_at);

COMMENT ON TABLE pas.policy_audit IS
    'AB-526 Q-15: replaces the ENDORSEMENT-AUDIT CICS TS queue; written in the business transaction. details holds no PII.';

CREATE TABLE pas.policy_renewals (
    policy_number CHAR(12) NOT NULL,
    prior_expiry_date DATE NOT NULL,
    renewal_count INTEGER NOT NULL,
    prior_effective_date DATE,
    new_effective_date DATE NOT NULL,
    new_expiry_date DATE NOT NULL,
    prior_premium NUMERIC(11,2) NOT NULL,
    new_premium NUMERIC(11,2) NOT NULL,
    rate_change_pct NUMERIC(5,2) NOT NULL,
    open_claims INTEGER NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_by VARCHAR(255) NOT NULL,
    CONSTRAINT pk_policy_renewals PRIMARY KEY (policy_number, prior_expiry_date),
    CONSTRAINT fk_renewal_policy FOREIGN KEY (policy_number)
        REFERENCES pas.policies (policy_number)
);

COMMENT ON TABLE pas.policy_renewals IS
    'AB-526 Q-17: one row per renewed term; a second renewal of the same term is rejected with 409.';
