TRUNCATE pas.coverages, pas.policies CASCADE;

INSERT INTO pas.policies
    (policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id,
     agent_code, branch_code, total_premium, deductible, coverage_limit, uw_status, risk_score,
     last_updated)
VALUES
    ('AB0000000001', 'HOM', 'AC', DATE '2020-01-01', DATE '2030-01-01', 'H000000001',
     NULL, NULL, -12.34, 500.00, 250000.00, 'AP', 7, TIMESTAMPTZ '2025-02-09 05:00:00+00'),
    ('AB0000000002', 'AUT', 'PN', DATE '2021-03-01', DATE '2026-03-01', 'H000000002',
     'AG0002', 'B002', 100.00, 250.00, 50000.00, 'PN', 3, TIMESTAMPTZ '2025-02-09 05:01:00+00'),
    ('AB0000000003', 'CGL', 'AC', DATE '2022-04-01', DATE '2027-04-01', 'H000000003',
     'AG0003', 'B003', 50.00, 100.00, 10000.00, 'AP', 1, TIMESTAMPTZ '2025-02-09 04:59:59+00'),
    ('AB0000000004', 'HOM', 'CN', DATE '2020-01-01', DATE '2030-01-01', 'H000000004',
     NULL, 'B004', 0.00, 0.00, 0.00, 'CN', 0, TIMESTAMPTZ '2025-02-09 06:00:00+00'),
    ('AB0000000005', 'CGL', 'EX', DATE '2020-01-01', DATE '2030-01-01', 'H000000005',
     'AG0005', 'B005', 25.00, 50.00, 5000.00, 'EX', 2, TIMESTAMPTZ '2025-02-09 06:00:00+00');

INSERT INTO pas.coverages
    (policy_number, sequence_num, coverage_type, description, coverage_limit, deductible,
     premium, effective_date, expiry_date, status, rating_territory, class_code)
VALUES
    ('AB0000000001', 1, 'FIRE', 'Dwelling', 100000.00, 500.00, 10.00,
     DATE '2020-01-01', DATE '2030-01-01', 'AC', NULL, NULL),
    ('AB0000000001', 2, 'FIRE', 'Contents', 150000.00, 500.00, 4.00,
     DATE '2020-01-01', DATE '2030-01-01', 'AC', NULL, NULL),
    ('AB0000000001', 3, 'FIRE', 'Excluded', 500000.00, 500.00, 10.00,
     DATE '2020-01-01', DATE '2030-01-01', 'EX', NULL, NULL),
    ('AB0000000002', 1, 'AUTO', 'Vehicle', 50000.00, 250.00, 100.00,
     DATE '2021-03-01', DATE '2026-03-01', 'AC', 'TERR01', 'AUTO1'),
    ('AB0000000004', 1, 'FIRE', 'Cancelled policy', 99999.00, 0.00, 0.00,
     DATE '2020-01-01', DATE '2030-01-01', 'AC', NULL, NULL),
    ('AB0000000005', 1, 'CGL', 'Excluded policy', 5000.00, 50.00, 25.00,
     DATE '2020-01-01', DATE '2030-01-01', 'AC', NULL, NULL);
