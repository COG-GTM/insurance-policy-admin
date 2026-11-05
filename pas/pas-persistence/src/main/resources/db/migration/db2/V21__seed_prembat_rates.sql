-- AB-522: the rates PREMBAT 3200-CALCULATE-PREMIUM applies today, as reference rows. Same values
-- as db/migration/postgresql/V21__seed_prembat_rates.sql; see that file for provenance.

INSERT INTO PASAPP.BASE_RATES (POLICY_TYPE, EFFECTIVE_DATE, BASE_RATE) VALUES
    ('AUT', DATE('0001-01-01'),  850.00),
    ('HOM', DATE('0001-01-01'), 1200.00),
    ('COM', DATE('0001-01-01'), 5000.00),
    ('LIF', DATE('0001-01-01'),  400.00),
    ('HLT', DATE('0001-01-01'), 3500.00);

INSERT INTO PASAPP.RATING_PARAMETERS (PARAMETER_CODE, EFFECTIVE_DATE, PARAMETER_VALUE) VALUES
    ('OTHER_BASE_RATE', DATE('0001-01-01'), 1000.00),
    ('TAX_RATE',        DATE('0001-01-01'),    0.0350),
    ('SURCHARGE_AMT',   DATE('0001-01-01'),   25.00);
