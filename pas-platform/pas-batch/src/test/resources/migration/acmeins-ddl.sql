CREATE SCHEMA IF NOT EXISTS acmeins;
CREATE SCHEMA IF NOT EXISTS sysibm;
CREATE OR REPLACE VIEW sysibm.sysdummy1 AS SELECT 'X'::CHAR(1) AS dummy;

CREATE TABLE acmeins.policy_holders (
    cust_id CHAR(10) PRIMARY KEY,
    cust_type CHAR(1) NOT NULL DEFAULT 'I',
    last_name VARCHAR(30),
    first_name VARCHAR(20),
    middle_init CHAR(1),
    company_name VARCHAR(50),
    addr_line1 VARCHAR(40),
    addr_line2 VARCHAR(40),
    city VARCHAR(25),
    state_code CHAR(2),
    zip_code CHAR(10),
    country_code CHAR(3) DEFAULT 'USA',
    phone VARCHAR(15),
    email VARCHAR(60),
    date_of_birth DATE,
    ssn_last4 CHAR(4),
    tax_id CHAR(10),
    credit_score SMALLINT,
    risk_tier CHAR(1) DEFAULT 'S',
    gdpr_consent CHAR(1) DEFAULT 'N',
    created_date DATE NOT NULL DEFAULT CURRENT_DATE,
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE acmeins.policies (
    policy_number CHAR(12) PRIMARY KEY,
    policy_type CHAR(3) NOT NULL,
    policy_status CHAR(2) NOT NULL DEFAULT 'PN',
    effective_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    policyholder_id CHAR(10) NOT NULL,
    agent_code CHAR(6),
    branch_code CHAR(4),
    total_premium NUMERIC(11,2) DEFAULT 0,
    deductible NUMERIC(9,2) DEFAULT 0,
    coverage_limit NUMERIC(13,2) DEFAULT 0,
    inception_date DATE,
    renewal_count SMALLINT DEFAULT 0,
    uw_status CHAR(2) DEFAULT 'PN',
    risk_score SMALLINT DEFAULT 0,
    web_indicator CHAR(1) DEFAULT 'N',
    api_flag CHAR(1) DEFAULT 'N',
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by CHAR(8) NOT NULL DEFAULT 'SYSTEM'
);

CREATE TABLE acmeins.coverages (
    policy_number CHAR(12) NOT NULL REFERENCES acmeins.policies(policy_number),
    sequence_num SMALLINT NOT NULL,
    coverage_type CHAR(4) NOT NULL,
    description VARCHAR(40),
    coverage_limit NUMERIC(13,2) DEFAULT 0,
    deductible NUMERIC(9,2) DEFAULT 0,
    premium NUMERIC(11,2) DEFAULT 0,
    effective_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    status CHAR(2) NOT NULL DEFAULT 'AC',
    coinsurance_pct SMALLINT DEFAULT 100,
    rating_territory CHAR(6),
    class_code CHAR(5),
    PRIMARY KEY (policy_number, sequence_num)
);

CREATE TABLE acmeins.premiums (
    policy_number CHAR(12) NOT NULL REFERENCES acmeins.policies(policy_number),
    coverage_seq SMALLINT NOT NULL,
    term_effective_date DATE NOT NULL,
    term_expiry_date DATE NOT NULL,
    base_rate NUMERIC(11,4) DEFAULT 0,
    territory_factor NUMERIC(7,4) DEFAULT 1.0000,
    class_factor NUMERIC(7,4) DEFAULT 1.0000,
    experience_mod NUMERIC(7,4) DEFAULT 1.0000,
    schedule_mod NUMERIC(7,4) DEFAULT 1.0000,
    discount_pct NUMERIC(5,2) DEFAULT 0,
    surcharge_amt NUMERIC(9,2) DEFAULT 0,
    tax_amt NUMERIC(9,2) DEFAULT 0,
    total_premium NUMERIC(11,2) DEFAULT 0,
    installment_code CHAR(2) DEFAULT 'AN',
    installment_amt NUMERIC(9,2) DEFAULT 0,
    calc_date DATE,
    calc_by CHAR(8),
    PRIMARY KEY (policy_number, coverage_seq, term_effective_date)
);

CREATE TABLE acmeins.endorsements (
    policy_number CHAR(12) NOT NULL REFERENCES acmeins.policies(policy_number),
    endorsement_seq INTEGER NOT NULL,
    endorsement_type CHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    description VARCHAR(100),
    premium_adjustment NUMERIC(11,2) DEFAULT 0,
    processed_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_by CHAR(8) NOT NULL,
    PRIMARY KEY (policy_number, endorsement_seq)
);

CREATE TABLE acmeins.underwriting_decisions (
    policy_number CHAR(12) NOT NULL REFERENCES acmeins.policies(policy_number),
    decision_date DATE NOT NULL,
    decision_code CHAR(2) NOT NULL,
    risk_score SMALLINT,
    decision_reason VARCHAR(100),
    underwriter_id CHAR(8),
    override_reason VARCHAR(200),
    override_by CHAR(8),
    created_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (policy_number, decision_date)
);

CREATE TABLE acmeins.territory_factors (
    territory_code CHAR(6) NOT NULL,
    effective_date DATE NOT NULL,
    rating_factor NUMERIC(7,4) NOT NULL,
    PRIMARY KEY (territory_code, effective_date)
);

CREATE SEQUENCE acmeins.policy_seq AS INTEGER START WITH 1000000 INCREMENT BY 1;
