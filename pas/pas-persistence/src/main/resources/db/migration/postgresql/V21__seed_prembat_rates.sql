-- AB-522: the rates PREMBAT 3200-CALCULATE-PREMIUM applies today (cobol/programs/PREMBAT.cbl:
-- EVALUATE POL-POLICY-TYPE, WS-TAX-RATE VALUE 0.0350, MOVE 25.00 TO WS-SURCHARGE), recorded as the
-- reference data the Java rating component loads. The PREMBAT golden files (parity/golden/prembat-*)
-- prove at cent level that rating from these rows reproduces the COBOL.
-- effective_date 0001-01-01 means "in force since before the migration": the repo holds no rate
-- history. A rate change is a new row with a later effective_date, never an UPDATE of these rows.
-- Actuarial / Pricing confirm these are the filed production rates (sign-off pending, AB-522).

INSERT INTO pasapp.base_rates (policy_type, effective_date, base_rate) VALUES
    ('AUT', DATE '0001-01-01',  850.00),
    ('HOM', DATE '0001-01-01', 1200.00),
    ('COM', DATE '0001-01-01', 5000.00),
    ('LIF', DATE '0001-01-01',  400.00),
    ('HLT', DATE '0001-01-01', 3500.00);

INSERT INTO pasapp.rating_parameters (parameter_code, effective_date, parameter_value) VALUES
    ('OTHER_BASE_RATE', DATE '0001-01-01', 1000.00),
    ('TAX_RATE',        DATE '0001-01-01',    0.0350),
    ('SURCHARGE_AMT',   DATE '0001-01-01',   25.00);
