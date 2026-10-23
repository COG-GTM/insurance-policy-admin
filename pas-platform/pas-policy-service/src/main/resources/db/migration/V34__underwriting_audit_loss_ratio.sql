ALTER TABLE pas.underwriting_decisions ADD COLUMN loss_ratio NUMERIC(15,2);

UPDATE pas.underwriting_decisions SET decision_reasons = ARRAY[decision_reason]::text[] WHERE decision_reasons IS NULL AND decision_reason IS NOT NULL;
