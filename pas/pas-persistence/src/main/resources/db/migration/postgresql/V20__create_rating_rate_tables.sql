-- AB-522: rate reference tables for the Java rating component (pas-rating, ADR 0001 D2).
-- PREMBAT 3200-CALCULATE-PREMIUM hardcodes the base premium per policy type, the WHEN OTHER base,
-- the 3.5% premium tax and the 25.00 flat surcharge (quirk Q-27). These tables hold those values so
-- the Java code carries none. Both are effective-dated like the factor tables (V11): the rating
-- lookup is "latest effective_date on or before the rating date". Rating fails closed: a missing
-- parameter stops rating instead of defaulting.
-- Widths follow the COBOL receiving fields: WS-BASE-PREMIUM S9(09)V99 is capped at DECIMAL(9,2) so
-- every base also fits PREMIUMS.BASE_RATE DECIMAL(11,4); WS-TAX-RATE S9(01)V9999.

CREATE TABLE pasapp.base_rates (
    policy_type         char(3)         NOT NULL,
    effective_date      date            NOT NULL,
    base_rate           numeric(9,2)    NOT NULL,
    CONSTRAINT pk_base_rates PRIMARY KEY (policy_type, effective_date),
    CONSTRAINT ck_base_rate_not_negative CHECK (base_rate >= 0)
);

COMMENT ON TABLE pasapp.base_rates IS 'Base premium per POLICIES.POLICY_TYPE, effective-dated; lookup = latest effective_date <= rating date';

CREATE TABLE pasapp.rating_parameters (
    parameter_code      varchar(20)     NOT NULL,
    effective_date      date            NOT NULL,
    parameter_value     numeric(11,4)   NOT NULL,
    CONSTRAINT pk_rating_parameters PRIMARY KEY (parameter_code, effective_date),
    CONSTRAINT ck_rating_parameter_code CHECK (parameter_code IN ('OTHER_BASE_RATE', 'TAX_RATE', 'SURCHARGE_AMT')),
    CONSTRAINT ck_rating_parameter_not_negative CHECK (parameter_value >= 0),
    CONSTRAINT ck_rating_parameter_scale CHECK (
        (parameter_code = 'TAX_RATE' AND parameter_value < 10)
        OR (parameter_code <> 'TAX_RATE' AND parameter_value = trunc(parameter_value, 2)))
);

COMMENT ON TABLE pasapp.rating_parameters IS 'Scalar rating parameters (WHEN OTHER base, premium tax rate, flat surcharge), effective-dated';
