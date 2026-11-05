-- AB-523: per-policy outcome of a premium recalculation run (Spring Batch job premiumRecalculation,
-- replacing PREMIUM-BATCH / PREMBAT). One row per active policy the run read, keyed by the Spring
-- Batch job instance so a restart of the same run keeps the rows its committed chunks wrote. The
-- run report (PREMRPT) and its READ / UPDATED / ERRORS totals are produced from this table, so a
-- partitioned run still reports in policy-number order. status 'U' = PREMIUMS row written,
-- 'E' = the policy failed and the run continued (Q-25). No PII: policy number, amount and an error
-- class only. DB2 gets the same table in PASAPP (db/migration/db2/V30__create_premium_run_item.sql).

CREATE SCHEMA IF NOT EXISTS pasapp;

CREATE TABLE pasapp.premium_run_item (
    job_instance_id     bigint          NOT NULL,
    policy_number       char(12)        NOT NULL,
    business_date       date            NOT NULL,
    status              char(1)         NOT NULL,
    total_premium       numeric(11,2),
    error_code          varchar(64),
    detail              varchar(256),
    recorded_at         timestamp       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_premium_run_item PRIMARY KEY (job_instance_id, policy_number),
    CONSTRAINT ck_premium_run_item_status CHECK (status IN ('U', 'E')),
    CONSTRAINT ck_premium_run_item_total CHECK (status <> 'U' OR total_premium IS NOT NULL)
);

CREATE INDEX ix_premium_run_item_date ON pasapp.premium_run_item (business_date);
