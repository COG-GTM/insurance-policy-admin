-- AB-521: New factor-set model; territory source is sql/ddl/create-tables.sql lines 193-202.
-- The legacy key is widened by factor_set to support parallel rate sets.
CREATE TABLE pas.rating_factor_sets (
    set_code VARCHAR(16) PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_rating_factor_sets_active
    ON pas.rating_factor_sets (active) WHERE active;

CREATE TABLE pas.territory_factors (
    factor_set VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
        REFERENCES pas.rating_factor_sets (set_code),
    territory_code CHAR(6) NOT NULL,
    effective_date DATE NOT NULL,
    rating_factor NUMERIC(7,4) NOT NULL CHECK (rating_factor > 0),
    CONSTRAINT pk_terr_factors PRIMARY KEY (factor_set, territory_code, effective_date)
);

CREATE TABLE pas.class_factors (
    factor_set VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
        REFERENCES pas.rating_factor_sets (set_code),
    policy_type CHAR(3) NOT NULL,
    class_code CHAR(5) NOT NULL,
    effective_date DATE NOT NULL,
    rating_factor NUMERIC(7,4) NOT NULL CHECK (rating_factor > 0),
    description VARCHAR(100),
    CONSTRAINT pk_class_factors PRIMARY KEY (factor_set, policy_type, class_code, effective_date)
);

INSERT INTO pas.rating_factor_sets (set_code, description, active) VALUES
    ('LEGACY', 'Reproduces PREMBAT: territory/class factors 1.0000 (Q-03, Q-07)', true),
    ('DB2', 'Rows synced as-is from ACMEINS.TERRITORY_FACTORS; never read by PREMBAT (Q-03)', false),
    ('ILLUSTRATIVE', 'Sample territory/class relativities for parity/flag testing only — not a filed rate; Actuarial to supply (Q-03)', false);

INSERT INTO pas.territory_factors (factor_set, territory_code, effective_date, rating_factor) VALUES
    ('LEGACY', '*', DATE '1900-01-01', 1.0000),
    ('ILLUSTRATIVE', 'NY0001', DATE '1900-01-01', 1.2500),
    ('ILLUSTRATIVE', 'NY0002', DATE '1900-01-01', 1.1000),
    ('ILLUSTRATIVE', 'CA0001', DATE '1900-01-01', 1.3500),
    ('ILLUSTRATIVE', 'TX0001', DATE '1900-01-01', 0.9500),
    ('ILLUSTRATIVE', 'FL0001', DATE '1900-01-01', 1.4000),
    ('ILLUSTRATIVE', '*', DATE '1900-01-01', 1.0000);

INSERT INTO pas.class_factors
    (factor_set, policy_type, class_code, effective_date, rating_factor, description)
VALUES
    ('LEGACY', 'AUT', '*', DATE '1900-01-01', 1.0000, 'Legacy parity factor'),
    ('LEGACY', 'HOM', '*', DATE '1900-01-01', 1.0000, 'Legacy parity factor'),
    ('LEGACY', 'COM', '*', DATE '1900-01-01', 1.0000, 'Legacy parity factor'),
    ('LEGACY', 'LIF', '*', DATE '1900-01-01', 1.0000, 'Legacy parity factor'),
    ('LEGACY', 'HLT', '*', DATE '1900-01-01', 1.0000, 'Legacy parity factor'),
    ('LEGACY', '*', '*', DATE '1900-01-01', 1.0000, 'Legacy parity fallback'),
    ('ILLUSTRATIVE', 'AUT', '*', DATE '1900-01-01', 1.0000, 'Illustrative only'),
    ('ILLUSTRATIVE', 'AUT', 'PPA01', DATE '1900-01-01', 1.0500, 'Illustrative only'),
    ('ILLUSTRATIVE', 'HOM', 'FRM01', DATE '1900-01-01', 1.1500, 'Illustrative only'),
    ('ILLUSTRATIVE', 'COM', 'CGL01', DATE '1900-01-01', 1.2000, 'Illustrative only'),
    ('ILLUSTRATIVE', '*', '*', DATE '1900-01-01', 1.0000, 'Illustrative fallback');

COMMENT ON TABLE pas.rating_factor_sets IS 'Selects a single active set; set changes belong to AB-522 (pas.rating).';
COMMENT ON TABLE pas.territory_factors IS 'Territory key is widened by factor_set; * matches any territory.';
COMMENT ON TABLE pas.class_factors IS 'Within an active set, exact policy type/class wins over *; latest eligible effective date wins.';
