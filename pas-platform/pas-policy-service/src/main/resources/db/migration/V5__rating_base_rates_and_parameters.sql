-- AB-522: the rest of PREMBAT's rating inputs as reference data, so no rate is hardcoded in Java.
-- PREMBAT 3200-CALCULATE-PREMIUM hardcodes the base rate per policy type (EVALUATE ... WHEN OTHER),
-- WS-TAX-RATE (0.0350), the flat regulatory surcharge (25.00) and an experience mod of 1.00; it
-- writes SCHEDULE_MOD 1.0000 and DISCOUNT_PCT 0.00 to PREMIUMS without applying them. The seed rows
-- below reproduce those values exactly (rate version PREMBAT-3200) from the earliest representable
-- date. Territory/class factors stay in V3 (factor set selected by pas.rating.factor-set).
--
-- Rows are effective-dated: rating uses the latest row with effective_date <= rating date. A new
-- filing is a new row, never an UPDATE, so every PREMIUMS row can be traced to the rates used.
-- rating_parameters.rate_version is the filing identity: every base-rate/factor row must be
-- accompanied by a rating_parameters row effective on or after it, or the snapshot is refused.

CREATE TABLE pas.base_rates (
    policy_type    VARCHAR(3)   NOT NULL,
    effective_date DATE         NOT NULL,
    base_rate      NUMERIC(9,2) NOT NULL,
    CONSTRAINT pk_base_rates PRIMARY KEY (policy_type, effective_date),
    -- NUMERIC(9,2) fits WS-BASE-PREMIUM S9(9)V99 and PREM-BASE-RATE S9(7)V9999
    CONSTRAINT ck_base_rate_range CHECK (base_rate >= 0)
);

CREATE TABLE pas.rating_parameters (
    effective_date DATE         NOT NULL,
    rate_version   VARCHAR(32)  NOT NULL,
    tax_rate       NUMERIC(5,4) NOT NULL,
    surcharge_amt  NUMERIC(9,2) NOT NULL,
    experience_mod NUMERIC(7,4) NOT NULL,
    schedule_mod   NUMERIC(7,4) NOT NULL,
    discount_pct   NUMERIC(5,2) NOT NULL,
    CONSTRAINT pk_rating_parameters PRIMARY KEY (effective_date),
    CONSTRAINT uq_rating_parameters_version UNIQUE (rate_version),
    CONSTRAINT ck_rating_tax_range CHECK (tax_rate >= 0 AND tax_rate < 10),
    CONSTRAINT ck_rating_surcharge_range CHECK (surcharge_amt >= 0),
    CONSTRAINT ck_rating_experience_mod_positive CHECK (experience_mod > 0),
    CONSTRAINT ck_rating_schedule_mod_positive CHECK (schedule_mod > 0),
    CONSTRAINT ck_rating_discount_range CHECK (discount_pct >= 0 AND discount_pct < 100)
);

-- '*' is PREMBAT's WHEN OTHER branch (any type without its own row, incl. lowercase and blank).
INSERT INTO pas.base_rates (policy_type, effective_date, base_rate) VALUES
    ('AUT', DATE '0001-01-01', 850.00),
    ('HOM', DATE '0001-01-01', 1200.00),
    ('COM', DATE '0001-01-01', 5000.00),
    ('LIF', DATE '0001-01-01', 400.00),
    ('HLT', DATE '0001-01-01', 3500.00),
    ('*',   DATE '0001-01-01', 1000.00);

INSERT INTO pas.rating_parameters
    (effective_date, rate_version, tax_rate, surcharge_amt, experience_mod, schedule_mod, discount_pct)
VALUES
    (DATE '0001-01-01', 'PREMBAT-3200', 0.0350, 25.00, 1.0000, 1.0000, 0.00);

COMMENT ON TABLE pas.base_rates IS 'Base premium by policy type (PREMBAT 3200 EVALUATE); * = OTHER';
COMMENT ON TABLE pas.rating_parameters IS 'Tax rate, flat surcharge and modifiers by rate version';
