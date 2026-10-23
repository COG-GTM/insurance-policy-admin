INSERT INTO acmeins.policy_holders (
    cust_id, cust_type, last_name, first_name, addr_line1, city, state_code, zip_code,
    country_code, phone, email, date_of_birth, ssn_last4, tax_id, credit_score, risk_tier,
    gdpr_consent, created_date, last_updated
) VALUES (
    'C529000001', 'I', 'Migration', 'Rehearsal', '1 Test Way', 'Albany', 'NY', '12207',
    'USA', '5550100', 'migration@example.invalid', DATE '1980-01-01', '1234',
    '1234567890', 720, 'S', 'Y', DATE '2025-01-01', TIMESTAMP '2025-01-01 12:00:00'
);

INSERT INTO acmeins.policies (
    policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id,
    agent_code, branch_code, total_premium, deductible, coverage_limit, inception_date,
    renewal_count, uw_status, risk_score, web_indicator, api_flag, last_updated, updated_by
) VALUES (
    'POL529000001', 'AUT', 'AC', DATE '2025-01-01', DATE '2026-01-01', 'C529000001',
    'AGT001', 'B001', 1200.00, 500.00, 100000.00, DATE '2025-01-01',
    0, 'AP', 720, 'N', 'N', TIMESTAMP '2025-01-01 12:00:00', 'SEED'
);

INSERT INTO acmeins.coverages (
    policy_number, sequence_num, coverage_type, description, coverage_limit, deductible,
    premium, effective_date, expiry_date, status, coinsurance_pct, rating_territory, class_code
) VALUES (
    'POL529000001', 1, 'AUTO', 'Rehearsal coverage', 100000.00, 500.00, 1200.00,
    DATE '2025-01-01', DATE '2026-01-01', 'AC', 100, 'NY0001', 'AUTO1'
);

INSERT INTO acmeins.premiums (
    policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
    territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
    surcharge_amt, tax_amt, total_premium, installment_code, installment_amt, calc_date, calc_by
) VALUES (
    'POL529000001', 1, DATE '2025-01-01', DATE '2026-01-01', 1000.0000,
    1.0000, 1.0000, 1.0000, 1.0000, 0.00, 25.00, 35.00, 1060.00,
    'AN', 1060.00, DATE '2025-01-01', 'SEED'
);

INSERT INTO acmeins.endorsements (
    policy_number, endorsement_seq, endorsement_type, effective_date, description,
    premium_adjustment, processed_date, processed_by
) VALUES (
    'POL529000001', 1, 'ADD', DATE '2025-01-01', 'Rehearsal endorsement',
    0.00, TIMESTAMP '2025-01-01 12:00:00', 'SEED'
);

INSERT INTO acmeins.underwriting_decisions (
    policy_number, decision_date, decision_code, risk_score, decision_reason, underwriter_id,
    created_timestamp
) VALUES (
    'POL529000001', DATE '2025-01-01', 'AP', 720, 'Rehearsal seed', 'SEED',
    TIMESTAMP '2025-01-01 12:00:00'
);

INSERT INTO acmeins.territory_factors (territory_code, effective_date, rating_factor)
VALUES ('NY0001', DATE '2025-01-01', 1.0000);
