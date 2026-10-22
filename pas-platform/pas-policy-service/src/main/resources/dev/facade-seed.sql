INSERT INTO pas.policies (
    policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id,
    agent_code, branch_code, total_premium, deductible, coverage_limit, inception_date,
    renewal_count, uw_status, risk_score, web_indicator, api_flag, updated_by
) VALUES
    ('POL-00000001', 'HOM', 'AC', DATE '2025-01-01', DATE '2026-01-01', 'C000000001', 'AG1001', 'CHI1', 1250.00, 1000.00, 500000.00, DATE '2020-01-01', 5, 'AP', 35, 'Y', 'Y', 'TNGUYEN'),
    ('POL-00000002', 'AUT', 'AC', DATE '2025-03-15', DATE '2026-03-15', 'C000000002', 'AG2005', 'CHI1', 890.50, 500.00, 100000.00, DATE '2023-03-15', 2, 'AP', 42, 'N', 'Y', 'TNGUYEN'),
    ('POL-00000003', 'CGL', 'AC', DATE '2025-06-01', DATE '2026-06-01', 'C000000003', 'AG3010', 'DET1', 4500.00, 2500.00, 2000000.00, DATE '2022-06-01', 3, 'AP', 55, 'Y', 'Y', 'TNGUYEN')
ON CONFLICT (policy_number) DO NOTHING;

INSERT INTO pas.coverages (
    policy_number, sequence_num, coverage_type, description, coverage_limit, deductible,
    premium, effective_date, expiry_date, status, coinsurance_pct, rating_territory, class_code
) VALUES
    ('POL-00000001', 1, 'DWEL', 'Dwelling Coverage', 400000.00, 1000.00, 850.00, DATE '2025-01-01', DATE '2026-01-01', 'AC', 80, 'IL0627', 'R1001'),
    ('POL-00000001', 2, 'PERS', 'Personal Property', 100000.00, 500.00, 400.00, DATE '2025-01-01', DATE '2026-01-01', 'AC', 100, 'IL0627', 'R1001'),
    ('POL-00000002', 1, 'LIAB', 'Liability', 50000.00, 0.00, 450.50, DATE '2025-03-15', DATE '2026-03-15', 'AC', 100, 'IL0601', 'A2010'),
    ('POL-00000002', 2, 'COLL', 'Collision', 50000.00, 500.00, 440.00, DATE '2025-03-15', DATE '2026-03-15', 'AC', 100, 'IL0601', 'A2010'),
    ('POL-00000003', 1, 'PREM', 'Premises Liability', 1000000.00, 2500.00, 2800.00, DATE '2025-06-01', DATE '2026-06-01', 'AC', 100, 'MI0482', 'C5001'),
    ('POL-00000003', 2, 'PROD', 'Products Liability', 1000000.00, 2500.00, 1700.00, DATE '2025-06-01', DATE '2026-06-01', 'AC', 100, 'MI0482', 'C5001')
ON CONFLICT (policy_number, sequence_num) DO NOTHING;
