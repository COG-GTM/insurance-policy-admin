-- AB-522: rate reference tables on DB2 for z/OS, in schema PASAPP (ACMEINS DDL in sql/ is frozen).
-- Same shape as db/migration/postgresql/V20__create_rating_rate_tables.sql. Applied by the DBA
-- team through change management while DB2 is the system of record; tablespace and buffer pool
-- are chosen by the DBA team at review.

CREATE TABLE PASAPP.BASE_RATES (
    POLICY_TYPE         CHAR(3)         NOT NULL,
    EFFECTIVE_DATE      DATE            NOT NULL,
    BASE_RATE           DECIMAL(9,2)    NOT NULL,
    CONSTRAINT PK_BASE_RATES PRIMARY KEY (POLICY_TYPE, EFFECTIVE_DATE),
    CONSTRAINT CK_BASE_RATE_NNEG CHECK (BASE_RATE >= 0)
);

CREATE UNIQUE INDEX PASAPP.IX_BASE_RATES
    ON PASAPP.BASE_RATES (POLICY_TYPE, EFFECTIVE_DATE);

CREATE TABLE PASAPP.RATING_PARAMETERS (
    PARAMETER_CODE      VARCHAR(20)     NOT NULL,
    EFFECTIVE_DATE      DATE            NOT NULL,
    PARAMETER_VALUE     DECIMAL(11,4)   NOT NULL,
    CONSTRAINT PK_RATING_PARMS PRIMARY KEY (PARAMETER_CODE, EFFECTIVE_DATE),
    CONSTRAINT CK_RATING_PARM_CODE CHECK (PARAMETER_CODE IN ('OTHER_BASE_RATE', 'TAX_RATE', 'SURCHARGE_AMT')),
    CONSTRAINT CK_RATING_PARM_NNEG CHECK (PARAMETER_VALUE >= 0),
    -- Same rule as ck_rating_parameter_scale on PostgreSQL: TAX_RATE fits S9(01)V9999, the money
    -- parameters have at most 2 decimals. Written with a CAST (truncates to scale 2) instead of a
    -- built-in function, which DB2 for z/OS check constraints do not allow.
    CONSTRAINT CK_RATING_PARM_SCALE CHECK (
        (PARAMETER_CODE = 'TAX_RATE' AND PARAMETER_VALUE < 10)
        OR (PARAMETER_CODE <> 'TAX_RATE' AND PARAMETER_VALUE = CAST(PARAMETER_VALUE AS DECIMAL(11,2))))
);

CREATE UNIQUE INDEX PASAPP.IX_RATING_PARMS
    ON PASAPP.RATING_PARAMETERS (PARAMETER_CODE, EFFECTIVE_DATE);
