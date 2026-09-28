------------------------------------------------------------------------
-- H2-compatible schema derived from the DB2 z/OS DDL.
-- Used when running with the "local" Spring profile.
------------------------------------------------------------------------

-- Keep the in-memory database alive between connections. Set here rather
-- than in the JDBC URL because only the admin account may change it.
SET DB_CLOSE_DELAY -1;

CREATE SCHEMA IF NOT EXISTS ACMEINS;

------------------------------------------------------------------------
-- POLICY_HOLDERS
--
-- DATE_OF_BIRTH, SSN_LAST4 and TAX_ID hold AES-256 ciphertext only
-- (ENCRYPT_DATAKEY on DB2 z/OS; see sql/ddl/create-tables.sql). H2 has no
-- equivalent, so local seed data leaves them NULL. CREDIT_SCORE stays
-- numeric for underwriting and is protected by grants below (H2) and a
-- column mask (DB2).
------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ACMEINS.POLICY_HOLDERS (
    CUST_ID             CHAR(10)        NOT NULL,
    CUST_TYPE           CHAR(1)         NOT NULL DEFAULT 'I',
    LAST_NAME           VARCHAR(30),
    FIRST_NAME          VARCHAR(20),
    MIDDLE_INIT         CHAR(1),
    COMPANY_NAME        VARCHAR(50),
    ADDR_LINE1          VARCHAR(40),
    ADDR_LINE2          VARCHAR(40),
    CITY                VARCHAR(25),
    STATE_CODE          CHAR(2),
    ZIP_CODE            CHAR(10),
    COUNTRY_CODE        CHAR(3)         DEFAULT 'USA',
    PHONE               VARCHAR(15),
    EMAIL               VARCHAR(60),
    DATE_OF_BIRTH       VARBINARY(95),
    SSN_LAST4           VARBINARY(95),
    TAX_ID              VARBINARY(95),
    CREDIT_SCORE        SMALLINT,
    RISK_TIER           CHAR(1)         DEFAULT 'S',
    GDPR_CONSENT        CHAR(1)         DEFAULT 'N',
    CREATED_DATE        DATE            NOT NULL DEFAULT CURRENT_DATE,
    LAST_UPDATED        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_POLICY_HOLDERS PRIMARY KEY (CUST_ID)
);

------------------------------------------------------------------------
-- POLICIES
------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ACMEINS.POLICIES (
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    POLICY_TYPE         CHAR(3)         NOT NULL,
    POLICY_STATUS       CHAR(2)         NOT NULL DEFAULT 'PN',
    EFFECTIVE_DATE      DATE            NOT NULL,
    EXPIRY_DATE         DATE            NOT NULL,
    POLICYHOLDER_ID     CHAR(10)        NOT NULL,
    AGENT_CODE          CHAR(6),
    BRANCH_CODE         CHAR(4),
    TOTAL_PREMIUM       DECIMAL(11,2)   DEFAULT 0,
    DEDUCTIBLE          DECIMAL(9,2)    DEFAULT 0,
    COVERAGE_LIMIT      DECIMAL(13,2)   DEFAULT 0,
    INCEPTION_DATE      DATE,
    RENEWAL_COUNT       SMALLINT        DEFAULT 0,
    UW_STATUS           CHAR(2)         DEFAULT 'PN',
    RISK_SCORE          SMALLINT        DEFAULT 0,
    WEB_INDICATOR       CHAR(1)         DEFAULT 'N',
    API_FLAG            CHAR(1)         DEFAULT 'N',
    LAST_UPDATED        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATED_BY          CHAR(8)         NOT NULL DEFAULT 'SYSTEM',
    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER),
    CONSTRAINT FK_POL_HOLDER FOREIGN KEY (POLICYHOLDER_ID)
        REFERENCES ACMEINS.POLICY_HOLDERS (CUST_ID)
);

CREATE INDEX IF NOT EXISTS ACMEINS.IX_POL_STATUS
    ON ACMEINS.POLICIES (POLICY_STATUS, EXPIRY_DATE);

CREATE INDEX IF NOT EXISTS ACMEINS.IX_POL_HOLDER
    ON ACMEINS.POLICIES (POLICYHOLDER_ID);

CREATE INDEX IF NOT EXISTS ACMEINS.IX_POL_AGENT
    ON ACMEINS.POLICIES (AGENT_CODE);

------------------------------------------------------------------------
-- COVERAGES
------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ACMEINS.COVERAGES (
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    SEQUENCE_NUM        SMALLINT        NOT NULL,
    COVERAGE_TYPE       CHAR(4)         NOT NULL,
    DESCRIPTION         VARCHAR(40),
    COVERAGE_LIMIT      DECIMAL(13,2)   DEFAULT 0,
    DEDUCTIBLE          DECIMAL(9,2)    DEFAULT 0,
    PREMIUM             DECIMAL(11,2)   DEFAULT 0,
    EFFECTIVE_DATE      DATE            NOT NULL,
    EXPIRY_DATE         DATE            NOT NULL,
    STATUS              CHAR(2)         NOT NULL DEFAULT 'AC',
    COINSURANCE_PCT     SMALLINT        DEFAULT 100,
    RATING_TERRITORY    CHAR(6),
    CLASS_CODE          CHAR(5),
    CONSTRAINT PK_COVERAGES PRIMARY KEY (POLICY_NUMBER, SEQUENCE_NUM),
    CONSTRAINT FK_COV_POLICY FOREIGN KEY (POLICY_NUMBER)
        REFERENCES ACMEINS.POLICIES (POLICY_NUMBER)
);

------------------------------------------------------------------------
-- PREMIUMS
------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ACMEINS.PREMIUMS (
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    COVERAGE_SEQ        SMALLINT        NOT NULL,
    TERM_EFFECTIVE_DATE DATE            NOT NULL,
    TERM_EXPIRY_DATE    DATE            NOT NULL,
    BASE_RATE           DECIMAL(11,4)   DEFAULT 0,
    TERRITORY_FACTOR    DECIMAL(7,4)    DEFAULT 1.0000,
    CLASS_FACTOR        DECIMAL(7,4)    DEFAULT 1.0000,
    EXPERIENCE_MOD      DECIMAL(7,4)    DEFAULT 1.0000,
    SCHEDULE_MOD        DECIMAL(7,4)    DEFAULT 1.0000,
    DISCOUNT_PCT        DECIMAL(5,2)    DEFAULT 0,
    SURCHARGE_AMT       DECIMAL(9,2)    DEFAULT 0,
    TAX_AMT             DECIMAL(9,2)    DEFAULT 0,
    TOTAL_PREMIUM       DECIMAL(11,2)   DEFAULT 0,
    INSTALLMENT_CODE    CHAR(2)         DEFAULT 'AN',
    INSTALLMENT_AMT     DECIMAL(9,2)    DEFAULT 0,
    CALC_DATE           DATE,
    CALC_BY             CHAR(8),
    CONSTRAINT PK_PREMIUMS PRIMARY KEY
        (POLICY_NUMBER, COVERAGE_SEQ, TERM_EFFECTIVE_DATE),
    CONSTRAINT FK_PREM_POLICY FOREIGN KEY (POLICY_NUMBER)
        REFERENCES ACMEINS.POLICIES (POLICY_NUMBER)
);

------------------------------------------------------------------------
-- ENDORSEMENTS
------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ACMEINS.ENDORSEMENTS (
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    ENDORSEMENT_SEQ     INTEGER         NOT NULL,
    ENDORSEMENT_TYPE    CHAR(3)         NOT NULL,
    EFFECTIVE_DATE      DATE            NOT NULL,
    DESCRIPTION         VARCHAR(100),
    PREMIUM_ADJUSTMENT  DECIMAL(11,2)   DEFAULT 0,
    PROCESSED_DATE      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PROCESSED_BY        CHAR(8)         NOT NULL,
    CONSTRAINT PK_ENDORSEMENTS PRIMARY KEY
        (POLICY_NUMBER, ENDORSEMENT_SEQ),
    CONSTRAINT FK_END_POLICY FOREIGN KEY (POLICY_NUMBER)
        REFERENCES ACMEINS.POLICIES (POLICY_NUMBER)
);

------------------------------------------------------------------------
-- UNDERWRITING_DECISIONS
------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ACMEINS.UNDERWRITING_DECISIONS (
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    DECISION_DATE       DATE            NOT NULL,
    DECISION_CODE       CHAR(2)         NOT NULL,
    RISK_SCORE          SMALLINT,
    DECISION_REASON     VARCHAR(100),
    UNDERWRITER_ID      CHAR(8),
    OVERRIDE_REASON     VARCHAR(200),
    OVERRIDE_BY         CHAR(8),
    CREATED_TIMESTAMP   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_UW_DECISIONS PRIMARY KEY
        (POLICY_NUMBER, DECISION_DATE),
    CONSTRAINT FK_UWD_POLICY FOREIGN KEY (POLICY_NUMBER)
        REFERENCES ACMEINS.POLICIES (POLICY_NUMBER)
);

------------------------------------------------------------------------
-- TERRITORY_FACTORS
------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ACMEINS.TERRITORY_FACTORS (
    TERRITORY_CODE      CHAR(6)         NOT NULL,
    EFFECTIVE_DATE      DATE            NOT NULL,
    RATING_FACTOR       DECIMAL(7,4)    NOT NULL,
    CONSTRAINT PK_TERR_FACTORS PRIMARY KEY
        (TERRITORY_CODE, EFFECTIVE_DATE)
);

------------------------------------------------------------------------
-- Least-privilege account for the REST facade (mirrors the DB2 grants).
-- The facade only reads policies and coverages; it has no access to
-- POLICY_HOLDERS or any other table.
------------------------------------------------------------------------
CREATE USER IF NOT EXISTS PASFACAD PASSWORD '';
GRANT SELECT ON ACMEINS.POLICIES TO PASFACAD;
GRANT SELECT ON ACMEINS.COVERAGES TO PASFACAD;

------------------------------------------------------------------------
-- Policy number sequence
------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ACMEINS.POLICY_SEQ
    START WITH 1000000
    INCREMENT BY 1;
