-- AB-521: rating reference data. TERRITORY_FACTORS is ported from ACMEINS and CLASS_FACTORS is new
-- (PREMBAT declares WS-CLASS-TABLE but has no DB2 source for it).
--
-- Both tables carry a FACTOR_SET so several factor sets can coexist:
--   PARITY - reproduces today's PREMBAT output. PREMBAT opens the TERRITORY_FACTORS cursor but never
--            fetches it and multiplies by the literal 1.00 for both territory and class, so every
--            PARITY factor is 1.0000. A '*' code is the catch-all for any territory/class.
--   FILED  - the "real" factors. Neither the COBOL source nor the DB2 DDL carries any factor values
--            (WS-TERRITORY-TABLE / WS-CLASS-TABLE have no VALUE clauses), so this set ships empty and
--            is loaded from the rating department's filed tables by a follow-up.
-- Which set rating reads is selected by `pas.rating.factor-set` (default PARITY). AB-522 consumes it;
-- switching to FILED is a separate, flag-guarded change that stays off by default.
-- Within one factor set, the DB2 primary key (TERRITORY_CODE, EFFECTIVE_DATE) is preserved.

CREATE TABLE pas.rating_factor_sets (
    factor_set  VARCHAR(16)  NOT NULL,
    description VARCHAR(200) NOT NULL,
    CONSTRAINT pk_rating_factor_sets PRIMARY KEY (factor_set)
);

INSERT INTO pas.rating_factor_sets (factor_set, description) VALUES
    ('PARITY', 'Reproduces current PREMBAT output: territory and class factors are 1.0000'),
    ('FILED', 'Filed territory/class factors; empty until loaded, enabled only via pas.rating.factor-set');

CREATE TABLE pas.territory_factors (
    factor_set     VARCHAR(16)  NOT NULL DEFAULT 'PARITY',
    territory_code CHAR(6)      NOT NULL,
    effective_date DATE         NOT NULL,
    rating_factor  NUMERIC(7,4) NOT NULL,
    CONSTRAINT pk_terr_factors PRIMARY KEY (factor_set, territory_code, effective_date),
    CONSTRAINT fk_terr_factor_set FOREIGN KEY (factor_set)
        REFERENCES pas.rating_factor_sets (factor_set),
    CONSTRAINT ck_terr_factor_positive CHECK (rating_factor > 0)
);

CREATE TABLE pas.class_factors (
    factor_set     VARCHAR(16)  NOT NULL DEFAULT 'PARITY',
    class_code     CHAR(5)      NOT NULL,
    effective_date DATE         NOT NULL,
    rating_factor  NUMERIC(7,4) NOT NULL,
    CONSTRAINT pk_class_factors PRIMARY KEY (factor_set, class_code, effective_date),
    CONSTRAINT fk_class_factor_set FOREIGN KEY (factor_set)
        REFERENCES pas.rating_factor_sets (factor_set),
    CONSTRAINT ck_class_factor_positive CHECK (rating_factor > 0)
);

-- Parity seed: catch-all 1.0000 from the earliest representable date.
INSERT INTO pas.territory_factors (factor_set, territory_code, effective_date, rating_factor)
VALUES ('PARITY', '*', DATE '0001-01-01', 1.0000);
INSERT INTO pas.class_factors (factor_set, class_code, effective_date, rating_factor)
VALUES ('PARITY', '*', DATE '0001-01-01', 1.0000);

COMMENT ON TABLE pas.territory_factors IS 'Port of ACMEINS.TERRITORY_FACTORS, keyed by factor set';
COMMENT ON TABLE pas.class_factors IS 'Class-code rating factors (new in AB-521), keyed by factor set';
