-- AB-521: class-factor reference table on DB2 for z/OS, in schema PASAPP (never ACMEINS, whose
-- DDL in sql/ is frozen). Applied by the DBA team through change management while DB2 is the
-- system of record; PostgreSQL gets the same table in V11__create_rating_reference_tables.sql.
-- Tablespace and buffer pool are chosen by the DBA team at review.
-- TERRITORY_FACTORS stays as defined in sql/ddl/create-tables.sql; the positive-factor CHECK is
-- PostgreSQL-only until the DBA team agrees an ALTER on the legacy table.

CREATE TABLE PASAPP.CLASS_FACTORS (
    CLASS_CODE          CHAR(5)         NOT NULL,
    EFFECTIVE_DATE      DATE            NOT NULL,
    RATING_FACTOR       DECIMAL(7,4)    NOT NULL,
    CONSTRAINT PK_CLASS_FACTORS PRIMARY KEY (CLASS_CODE, EFFECTIVE_DATE),
    CONSTRAINT CK_CLASS_FACTOR_POS CHECK (RATING_FACTOR > 0)
);

CREATE UNIQUE INDEX PASAPP.IX_CLASS_FACTORS
    ON PASAPP.CLASS_FACTORS (CLASS_CODE, EFFECTIVE_DATE);
