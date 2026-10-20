-- AB-529: DB2 -> PostgreSQL migration, ongoing delta sync, reconciliation and parallel run.
-- Owned here (pas-policy-service owns every pas/pas_shadow migration); written by pas-batch.

-- Per-source-table high-water marks for the scheduled timestamp delta sync (CDC fallback).
CREATE TABLE pas.migration_watermarks (
    source_table    VARCHAR(40)  NOT NULL,
    watermark_ts    TIMESTAMP(6),
    rows_applied    BIGINT       NOT NULL DEFAULT 0,
    last_run_at     TIMESTAMP(6) NOT NULL DEFAULT LOCALTIMESTAMP,
    last_run_id     VARCHAR(64),
    CONSTRAINT pk_migration_watermarks PRIMARY KEY (source_table)
);

-- Single control row: rollback pauses the DB2 -> pas sync here before re-pointing at DB2.
CREATE TABLE pas.migration_sync_control (
    sync_name     VARCHAR(40)  NOT NULL,
    paused        BOOLEAN      NOT NULL DEFAULT FALSE,
    paused_reason VARCHAR(200),
    updated_at    TIMESTAMP(6) NOT NULL DEFAULT LOCALTIMESTAMP,
    updated_by    VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM',
    CONSTRAINT pk_migration_sync_control PRIMARY KEY (sync_name)
);

INSERT INTO pas.migration_sync_control (sync_name, paused) VALUES ('DB2_TO_PAS', FALSE);

-- One row per reconciliation / parallel-run check. Report bodies live in the report directory;
-- summary_json carries the classification counts the team dashboard and actuator read.
CREATE TABLE pas.migration_check_runs (
    run_id            VARCHAR(64)  NOT NULL,
    check_type        VARCHAR(20)  NOT NULL,
    business_date     DATE         NOT NULL,
    started_at        TIMESTAMP(6) NOT NULL,
    finished_at       TIMESTAMP(6) NOT NULL,
    status            VARCHAR(10)  NOT NULL,
    differences       INTEGER      NOT NULL DEFAULT 0,
    bug_count         INTEGER      NOT NULL DEFAULT 0,
    approved_fix_count INTEGER     NOT NULL DEFAULT 0,
    data_issue_count  INTEGER      NOT NULL DEFAULT 0,
    unclassified_count INTEGER     NOT NULL DEFAULT 0,
    unexplained_count INTEGER      NOT NULL DEFAULT 0,
    report_json_path  VARCHAR(500),
    report_md_path    VARCHAR(500),
    summary_json      TEXT,
    CONSTRAINT pk_migration_check_runs PRIMARY KEY (run_id),
    CONSTRAINT ck_mcr_check_type CHECK (check_type IN ('RECONCILIATION', 'PARALLEL_RUN')),
    CONSTRAINT ck_mcr_status CHECK (status IN ('PASS', 'FAIL', 'INCOMPLETE')),
    CONSTRAINT ck_mcr_unexplained CHECK (unexplained_count = bug_count + unclassified_count)
);

CREATE INDEX ix_mcr_type_date ON pas.migration_check_runs (check_type, business_date);

-- ADR-0001 D8 transitional shadow schema: new-platform outputs produced during the parallel run.
-- No foreign keys (reconciliation checks integrity); before the flip only the sync writes to pas.
CREATE SCHEMA IF NOT EXISTS pas_shadow;

CREATE TABLE pas_shadow.premiums (
    shadow_run_id       VARCHAR(64)   NOT NULL,
    business_date       DATE          NOT NULL,
    cdc_position        VARCHAR(64),
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
    CONSTRAINT pk_shadow_premiums
        PRIMARY KEY (shadow_run_id, policy_number, coverage_seq, term_effective_date)
);

CREATE INDEX ix_shadow_prem_date ON pas_shadow.premiums (business_date);

COMMENT ON SCHEMA pas_shadow IS 'AB-529 parallel-run staging; dropped by AB-530 after cutover';
