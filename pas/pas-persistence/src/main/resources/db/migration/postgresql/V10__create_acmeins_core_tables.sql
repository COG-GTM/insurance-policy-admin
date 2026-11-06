-- AB-521: port of the frozen DB2 DDL in sql/ddl/create-tables.sql to PostgreSQL 16.
-- Table, column, key, constraint, index and sequence names, types, nullability and defaults are
-- kept 1:1 so the CDC replica (ADR 0001 D3) and the parity harness see the same contract.
-- Type map: CHAR/VARCHAR/SMALLINT/INTEGER/DATE unchanged, DECIMAL(p,s) -> numeric(p,s),
-- TIMESTAMP -> timestamp(6), CURRENT TIMESTAMP -> localtimestamp, CURRENT DATE -> current_date.
-- DB2 tablespace clauses (ACMEDB.PASTS01) have no PostgreSQL equivalent and are dropped.
-- Deliberately NOT added: a POLICIES -> POLICY_HOLDERS foreign key (quirk Q-37) and any CHECK on
-- code columns (quirk Q-35). The Claims table is owned by the Claims team and is not ported.
-- See docs/pas/data-model.md.

CREATE TABLE acmeins.policies (
    policy_number       char(12)        NOT NULL,
    policy_type         char(3)         NOT NULL,
    policy_status       char(2)         NOT NULL DEFAULT 'PN',
    effective_date      date            NOT NULL,
    expiry_date         date            NOT NULL,
    policyholder_id     char(10)        NOT NULL,
    agent_code          char(6),
    branch_code         char(4),
    total_premium       numeric(11,2)   DEFAULT 0,
    deductible          numeric(9,2)    DEFAULT 0,
    coverage_limit      numeric(13,2)   DEFAULT 0,
    inception_date      date,
    renewal_count       smallint        DEFAULT 0,
    uw_status           char(2)         DEFAULT 'PN',
    risk_score          smallint        DEFAULT 0,
    web_indicator       char(1)         DEFAULT 'N',
    api_flag            char(1)         DEFAULT 'N',
    last_updated        timestamp(6)    NOT NULL DEFAULT localtimestamp,
    updated_by          char(8)         NOT NULL DEFAULT 'SYSTEM',
    CONSTRAINT pk_policies PRIMARY KEY (policy_number)
);

CREATE INDEX ix_pol_status ON acmeins.policies (policy_status, expiry_date);
CREATE INDEX ix_pol_holder ON acmeins.policies (policyholder_id);
CREATE INDEX ix_pol_agent ON acmeins.policies (agent_code);

CREATE TABLE acmeins.coverages (
    policy_number       char(12)        NOT NULL,
    sequence_num        smallint        NOT NULL,
    coverage_type       char(4)         NOT NULL,
    description         varchar(40),
    coverage_limit      numeric(13,2)   DEFAULT 0,
    deductible          numeric(9,2)    DEFAULT 0,
    premium             numeric(11,2)   DEFAULT 0,
    effective_date      date            NOT NULL,
    expiry_date         date            NOT NULL,
    status              char(2)         NOT NULL DEFAULT 'AC',
    coinsurance_pct     smallint        DEFAULT 100,
    rating_territory    char(6),
    class_code          char(5),
    CONSTRAINT pk_coverages PRIMARY KEY (policy_number, sequence_num),
    CONSTRAINT fk_cov_policy FOREIGN KEY (policy_number)
        REFERENCES acmeins.policies (policy_number)
);

CREATE TABLE acmeins.premiums (
    policy_number       char(12)        NOT NULL,
    coverage_seq        smallint        NOT NULL,
    term_effective_date date            NOT NULL,
    term_expiry_date    date            NOT NULL,
    base_rate           numeric(11,4)   DEFAULT 0,
    territory_factor    numeric(7,4)    DEFAULT 1.0000,
    class_factor        numeric(7,4)    DEFAULT 1.0000,
    experience_mod      numeric(7,4)    DEFAULT 1.0000,
    schedule_mod        numeric(7,4)    DEFAULT 1.0000,
    discount_pct        numeric(5,2)    DEFAULT 0,
    surcharge_amt       numeric(9,2)    DEFAULT 0,
    tax_amt             numeric(9,2)    DEFAULT 0,
    total_premium       numeric(11,2)   DEFAULT 0,
    installment_code    char(2)         DEFAULT 'AN',
    installment_amt     numeric(9,2)    DEFAULT 0,
    calc_date           date,
    calc_by             char(8),
    CONSTRAINT pk_premiums PRIMARY KEY (policy_number, coverage_seq, term_effective_date),
    CONSTRAINT fk_prem_policy FOREIGN KEY (policy_number)
        REFERENCES acmeins.policies (policy_number)
);

CREATE TABLE acmeins.endorsements (
    policy_number       char(12)        NOT NULL,
    endorsement_seq     integer         NOT NULL,
    endorsement_type    char(3)         NOT NULL,
    effective_date      date            NOT NULL,
    description         varchar(100),
    premium_adjustment  numeric(11,2)   DEFAULT 0,
    processed_date      timestamp(6)    NOT NULL DEFAULT localtimestamp,
    processed_by        char(8)         NOT NULL,
    CONSTRAINT pk_endorsements PRIMARY KEY (policy_number, endorsement_seq),
    CONSTRAINT fk_end_policy FOREIGN KEY (policy_number)
        REFERENCES acmeins.policies (policy_number)
);

CREATE TABLE acmeins.underwriting_decisions (
    policy_number       char(12)        NOT NULL,
    decision_date       date            NOT NULL,
    decision_code       char(2)         NOT NULL,
    risk_score          smallint,
    decision_reason     varchar(100),
    underwriter_id      char(8),
    override_reason     varchar(200),
    override_by         char(8),
    created_timestamp   timestamp(6)    NOT NULL DEFAULT localtimestamp,
    CONSTRAINT pk_uw_decisions PRIMARY KEY (policy_number, decision_date),
    CONSTRAINT fk_uwd_policy FOREIGN KEY (policy_number)
        REFERENCES acmeins.policies (policy_number)
);

CREATE TABLE acmeins.policy_holders (
    cust_id             char(10)        NOT NULL,
    cust_type           char(1)         NOT NULL DEFAULT 'I',
    last_name           varchar(30),
    first_name          varchar(20),
    middle_init         char(1),
    company_name        varchar(50),
    addr_line1          varchar(40),
    addr_line2          varchar(40),
    city                varchar(25),
    state_code          char(2),
    zip_code            char(10),
    country_code        char(3)         DEFAULT 'USA',
    phone               varchar(15),
    email               varchar(60),
    date_of_birth       date,
    ssn_last4           char(4),
    tax_id              char(10),
    credit_score        smallint,
    risk_tier           char(1)         DEFAULT 'S',
    gdpr_consent        char(1)         DEFAULT 'N',
    created_date        date            NOT NULL DEFAULT current_date,
    last_updated        timestamp(6)    NOT NULL DEFAULT localtimestamp,
    CONSTRAINT pk_policy_holders PRIMARY KEY (cust_id)
);

CREATE INDEX ix_ph_name ON acmeins.policy_holders (last_name, first_name);
CREATE INDEX ix_ph_company ON acmeins.policy_holders (company_name);

-- Same semantics as DB2: 32-bit, starts at 1000000, step 1, no cycle. CACHE 50 means a crash or
-- a session ending can leave gaps, as on DB2. Policy number formatting is not decided here (Q-08).
CREATE SEQUENCE acmeins.policy_seq
    AS integer
    START WITH 1000000
    INCREMENT BY 1
    NO MAXVALUE
    NO CYCLE
    CACHE 50;

CREATE TABLE acmeins.territory_factors (
    territory_code      char(6)         NOT NULL,
    effective_date      date            NOT NULL,
    rating_factor       numeric(7,4)    NOT NULL,
    CONSTRAINT pk_terr_factors PRIMARY KEY (territory_code, effective_date)
);

COMMENT ON SCHEMA acmeins IS 'Port of DB2 schema ACMEINS (sql/ddl/create-tables.sql) without CLAIMS, which the Claims team owns';
