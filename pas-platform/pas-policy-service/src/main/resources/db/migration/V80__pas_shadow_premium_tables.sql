-- AB-523: pas/pas_shadow DDL lives only in pas-policy-service per ADR 0001 D4.
-- ADR 0001 D4 rule 4: pas_shadow has no foreign keys to pas.
CREATE SCHEMA IF NOT EXISTS pas_shadow;

CREATE TABLE pas_shadow.premiums (
    policy_number CHAR(12) NOT NULL,
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
    business_date DATE NOT NULL,
    job_instance_id BIGINT NOT NULL,
    PRIMARY KEY (business_date, policy_number, coverage_seq, term_effective_date)
);

CREATE TABLE pas_shadow.premium_run_report (
    business_date DATE NOT NULL,
    job_instance_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    line_text VARCHAR(132) NOT NULL,
    PRIMARY KEY (business_date, line_no)
);
