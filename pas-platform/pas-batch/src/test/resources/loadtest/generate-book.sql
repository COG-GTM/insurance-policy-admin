-- AB-523 load-test book for premiumRecalculationJob.
-- Generates :active active policies (status 'AC') plus 10% non-active ones (status 'CN', must be
-- ignored by the job) with the PREMBAT policy-type mix. Policy numbers 'LT' + 10 digits.
--   psql -v active=1000000 -f generate-book.sql      (full-book size, see runbook)
-- PremiumJobLoadIT substitutes :active itself. Run only against a disposable load-test database.
INSERT INTO pas.policies (policy_number, policy_type, policy_status, effective_date, expiry_date,
                          policyholder_id, total_premium, deductible, coverage_limit)
SELECT 'LT' || lpad(g::text, 10, '0'),
       (ARRAY['AUT', 'HOM', 'COM', 'LIF', 'HLT', 'CGL'])[1 + g % 6],
       CASE WHEN g % 11 = 0 THEN 'CN' ELSE 'AC' END,
       DATE '2026-01-01' + (g % 365),
       DATE '2027-01-01' + (g % 365),
       'PH' || lpad((g % 100000000)::text, 8, '0'),
       1000.00, 500.00, 250000.00
FROM generate_series(1, (:active * 11) / 10) AS g;
ANALYZE pas.policies;
