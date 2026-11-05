-- AB-521: rating reference data for table-driven rating (AB-522, ADR 0001 D2).
-- TERRITORY_FACTORS exists in DB2 but PREMBAT never reads it (quirk Q-03). Here it becomes a real
-- reference table: factors must be positive and the lookup is "latest EFFECTIVE_DATE on or before
-- the rating date", served by the primary key index.
-- Class factors have no DB2 table today (PREMBAT hardcodes 1.00). The new table lives in schema
-- pasapp so acmeins keeps only the legacy contract; DB2 gets the same table in PASAPP
-- (db/migration/db2/V11__create_class_factors.sql).
-- No rows are seeded: authoritative factor values come from the business (FU-03). Until they are
-- loaded, rating uses the neutral factor 1.0000, which keeps parity with the COBOL.

ALTER TABLE acmeins.territory_factors
    ADD CONSTRAINT ck_terr_factor_positive CHECK (rating_factor > 0);

COMMENT ON TABLE acmeins.territory_factors IS 'Rating factor per territory, effective-dated; lookup = latest effective_date <= rating date';

CREATE SCHEMA IF NOT EXISTS pasapp;

CREATE TABLE pasapp.class_factors (
    class_code          char(5)         NOT NULL,
    effective_date      date            NOT NULL,
    rating_factor       numeric(7,4)    NOT NULL,
    CONSTRAINT pk_class_factors PRIMARY KEY (class_code, effective_date),
    CONSTRAINT ck_class_factor_positive CHECK (rating_factor > 0)
);

COMMENT ON TABLE pasapp.class_factors IS 'Rating factor per COVERAGES.CLASS_CODE, effective-dated; lookup = latest effective_date <= rating date';
