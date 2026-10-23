CREATE TABLE pas.rating_parameters (
    policy_type CHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    base_premium NUMERIC(11,2) NOT NULL CHECK (base_premium >= 0),
    experience_mod NUMERIC(7,4) NOT NULL CHECK (experience_mod > 0),
    schedule_mod NUMERIC(7,4) NOT NULL CHECK (schedule_mod > 0),
    tax_rate NUMERIC(5,4) NOT NULL CHECK (tax_rate >= 0),
    surcharge_amount NUMERIC(9,2) NOT NULL CHECK (surcharge_amount >= 0),
    source VARCHAR(200) NOT NULL,
    PRIMARY KEY (policy_type, effective_date)
);

COMMENT ON TABLE pas.rating_parameters IS
    'PREMBAT premium inputs transcribed from PREMBAT.cbl 3200-CALCULATE-PREMIUM (Q-07).';
COMMENT ON COLUMN pas.rating_parameters.policy_type IS
    '''*'' is the PREMBAT WHEN OTHER policy-type fallback (Q-07).';
COMMENT ON COLUMN pas.rating_parameters.experience_mod IS
    'PREMBAT experience modifier; seeded as 1.0000 from PREMBAT.cbl (Q-07).';
COMMENT ON COLUMN pas.rating_parameters.schedule_mod IS
    'Schedule modifier is stored for the port; PREMBAT does not apply it (Q-07).';

INSERT INTO pas.rating_parameters
    (policy_type, effective_date, base_premium, experience_mod, schedule_mod, tax_rate,
     surcharge_amount, source)
VALUES
    ('AUT', DATE '1900-01-01', 850.00, 1.0000, 1.0000, 0.0350, 25.00,
     'PREMBAT.cbl 3200-CALCULATE-PREMIUM (Q-07)'),
    ('HOM', DATE '1900-01-01', 1200.00, 1.0000, 1.0000, 0.0350, 25.00,
     'PREMBAT.cbl 3200-CALCULATE-PREMIUM (Q-07)'),
    ('COM', DATE '1900-01-01', 5000.00, 1.0000, 1.0000, 0.0350, 25.00,
     'PREMBAT.cbl 3200-CALCULATE-PREMIUM (Q-07)'),
    ('LIF', DATE '1900-01-01', 400.00, 1.0000, 1.0000, 0.0350, 25.00,
     'PREMBAT.cbl 3200-CALCULATE-PREMIUM (Q-07)'),
    ('HLT', DATE '1900-01-01', 3500.00, 1.0000, 1.0000, 0.0350, 25.00,
     'PREMBAT.cbl 3200-CALCULATE-PREMIUM (Q-07)'),
    ('*', DATE '1900-01-01', 1000.00, 1.0000, 1.0000, 0.0350, 25.00,
     'PREMBAT.cbl 3200-CALCULATE-PREMIUM (Q-07)');
