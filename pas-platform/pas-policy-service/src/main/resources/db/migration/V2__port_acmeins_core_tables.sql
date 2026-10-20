-- AB-521: port of sql/ddl/create-tables.sql (DB2 schema ACMEINS) into Postgres schema `pas`.
-- Table, column, key, index and constraint names are kept (lower-cased) so DB2 -> Postgres data
-- migration and reconciliation can map 1:1. Type mapping: CHAR/VARCHAR/SMALLINT/INTEGER/DATE kept,
-- DECIMAL(p,s) -> NUMERIC(p,s), TIMESTAMP -> TIMESTAMP(6) (DB2 microsecond precision),
-- CURRENT TIMESTAMP -> LOCALTIMESTAMP, CURRENT DATE -> CURRENT_DATE.
-- The DB2 DDL has no CHECK constraints; none are added on code columns so legacy rows (e.g. the
-- POLEND uninitialised endorsement type) still load. 88-level validation lives in the Java enums.
-- POLICY_HOLDERS regulated columns are ported as encrypted columns: see V4 and
-- docs/modernization/pii-handling.md. ACMEINS.CLAIMS is Claims-owned and intentionally absent:
-- see docs/modernization/claims-interface-assumptions.md.

-- POLNEW: SELECT NEXT VALUE FOR POLICY_SEQ; formatted by PolicyNumbers.fromSequence.
CREATE SEQUENCE pas.policy_seq
    AS INTEGER
    START WITH 1000000
    INCREMENT BY 1
    NO MAXVALUE
    NO CYCLE
    CACHE 50;

CREATE TABLE pas.policies (
    policy_number   CHAR(12)      NOT NULL,
    policy_type     CHAR(3)       NOT NULL,
    policy_status   CHAR(2)       NOT NULL DEFAULT 'PN',
    effective_date  DATE          NOT NULL,
    expiry_date     DATE          NOT NULL,
    policyholder_id CHAR(10)      NOT NULL,
    agent_code      CHAR(6),
    branch_code     CHAR(4),
    total_premium   NUMERIC(11,2) DEFAULT 0,
    deductible      NUMERIC(9,2)  DEFAULT 0,
    coverage_limit  NUMERIC(13,2) DEFAULT 0,
    inception_date  DATE,
    renewal_count   SMALLINT      DEFAULT 0,
    uw_status       CHAR(2)       DEFAULT 'PN',
    risk_score      SMALLINT      DEFAULT 0,
    web_indicator   CHAR(1)       DEFAULT 'N',
    api_flag        CHAR(1)       DEFAULT 'N',
    last_updated    TIMESTAMP(6)  NOT NULL DEFAULT LOCALTIMESTAMP,
    updated_by      CHAR(8)       NOT NULL DEFAULT 'SYSTEM',
    CONSTRAINT pk_policies PRIMARY KEY (policy_number)
);

CREATE INDEX ix_pol_status ON pas.policies (policy_status, expiry_date);
CREATE INDEX ix_pol_holder ON pas.policies (policyholder_id);
CREATE INDEX ix_pol_agent  ON pas.policies (agent_code);

CREATE TABLE pas.coverages (
    policy_number    CHAR(12)      NOT NULL,
    sequence_num     SMALLINT      NOT NULL,
    coverage_type    CHAR(4)       NOT NULL,
    description      VARCHAR(40),
    coverage_limit   NUMERIC(13,2) DEFAULT 0,
    deductible       NUMERIC(9,2)  DEFAULT 0,
    premium          NUMERIC(11,2) DEFAULT 0,
    effective_date   DATE          NOT NULL,
    expiry_date      DATE          NOT NULL,
    status           CHAR(2)       NOT NULL DEFAULT 'AC',
    coinsurance_pct  SMALLINT      DEFAULT 100,
    rating_territory CHAR(6),
    class_code       CHAR(5),
    CONSTRAINT pk_coverages PRIMARY KEY (policy_number, sequence_num),
    CONSTRAINT fk_cov_policy FOREIGN KEY (policy_number)
        REFERENCES pas.policies (policy_number)
);

CREATE TABLE pas.premiums (
    policy_number       CHAR(12)      NOT NULL,
    coverage_seq        SMALLINT      NOT NULL,
    term_effective_date DATE          NOT NULL,
    term_expiry_date    DATE          NOT NULL,
    base_rate           NUMERIC(11,4) DEFAULT 0,
    territory_factor    NUMERIC(7,4)  DEFAULT 1.0000,
    class_factor        NUMERIC(7,4)  DEFAULT 1.0000,
    experience_mod      NUMERIC(7,4)  DEFAULT 1.0000,
    schedule_mod        NUMERIC(7,4)  DEFAULT 1.0000,
    discount_pct        NUMERIC(5,2)  DEFAULT 0,
    surcharge_amt       NUMERIC(9,2)  DEFAULT 0,
    tax_amt             NUMERIC(9,2)  DEFAULT 0,
    total_premium       NUMERIC(11,2) DEFAULT 0,
    installment_code    CHAR(2)       DEFAULT 'AN',
    installment_amt     NUMERIC(9,2)  DEFAULT 0,
    calc_date           DATE,
    calc_by             CHAR(8),
    CONSTRAINT pk_premiums PRIMARY KEY (policy_number, coverage_seq, term_effective_date),
    CONSTRAINT fk_prem_policy FOREIGN KEY (policy_number)
        REFERENCES pas.policies (policy_number)
);

CREATE TABLE pas.endorsements (
    policy_number      CHAR(12)      NOT NULL,
    endorsement_seq    INTEGER       NOT NULL,
    endorsement_type   CHAR(3)       NOT NULL,
    effective_date     DATE          NOT NULL,
    description        VARCHAR(100),
    premium_adjustment NUMERIC(11,2) DEFAULT 0,
    processed_date     TIMESTAMP(6)  NOT NULL DEFAULT LOCALTIMESTAMP,
    processed_by       CHAR(8)       NOT NULL,
    CONSTRAINT pk_endorsements PRIMARY KEY (policy_number, endorsement_seq),
    CONSTRAINT fk_end_policy FOREIGN KEY (policy_number)
        REFERENCES pas.policies (policy_number)
);

CREATE TABLE pas.underwriting_decisions (
    policy_number     CHAR(12)     NOT NULL,
    decision_date     DATE         NOT NULL,
    decision_code     CHAR(2)      NOT NULL,
    risk_score        SMALLINT,
    decision_reason   VARCHAR(100),
    underwriter_id    CHAR(8),
    override_reason   VARCHAR(200),
    override_by       CHAR(8),
    created_timestamp TIMESTAMP(6) NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT pk_uw_decisions PRIMARY KEY (policy_number, decision_date),
    CONSTRAINT fk_uwd_policy FOREIGN KEY (policy_number)
        REFERENCES pas.policies (policy_number)
);

-- Non-regulated POLICY_HOLDERS columns. DATE_OF_BIRTH, SSN_LAST4, TAX_ID and CREDIT_SCORE are
-- added in V4 as encrypted columns.
CREATE TABLE pas.policy_holders (
    cust_id      CHAR(10)     NOT NULL,
    cust_type    CHAR(1)      NOT NULL DEFAULT 'I',
    last_name    VARCHAR(30),
    first_name   VARCHAR(20),
    middle_init  CHAR(1),
    company_name VARCHAR(50),
    addr_line1   VARCHAR(40),
    addr_line2   VARCHAR(40),
    city         VARCHAR(25),
    state_code   CHAR(2),
    zip_code     CHAR(10),
    country_code CHAR(3)      DEFAULT 'USA',
    phone        VARCHAR(15),
    email        VARCHAR(60),
    risk_tier    CHAR(1)      DEFAULT 'S',
    gdpr_consent CHAR(1)      DEFAULT 'N',
    created_date DATE         NOT NULL DEFAULT CURRENT_DATE,
    last_updated TIMESTAMP(6) NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT pk_policy_holders PRIMARY KEY (cust_id)
);

CREATE INDEX ix_ph_name    ON pas.policy_holders (last_name, first_name);
CREATE INDEX ix_ph_company ON pas.policy_holders (company_name);

COMMENT ON SEQUENCE pas.policy_seq IS 'Port of ACMEINS.POLICY_SEQ (POLNEW policy numbers)';
COMMENT ON TABLE pas.policies IS 'Port of ACMEINS.POLICIES (POLICY-RECORD.cpy)';
COMMENT ON TABLE pas.coverages IS 'Port of ACMEINS.COVERAGES (COVERAGE-RECORD.cpy)';
COMMENT ON TABLE pas.premiums IS 'Port of ACMEINS.PREMIUMS (PREMIUM-RECORD.cpy)';
COMMENT ON TABLE pas.endorsements IS 'Port of ACMEINS.ENDORSEMENTS';
COMMENT ON TABLE pas.underwriting_decisions IS 'Port of ACMEINS.UNDERWRITING_DECISIONS';
COMMENT ON TABLE pas.policy_holders IS 'Port of ACMEINS.POLICY_HOLDERS (CUSTOMER-RECORD.cpy); regulated PII';
