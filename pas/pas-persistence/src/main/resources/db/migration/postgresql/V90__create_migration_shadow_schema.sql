-- AB-529: schema acmeins_shadow (ADR 0001 DS-02) for the DB2 -> PostgreSQL migration: sync runs,
-- daily reconciliation results and the parallel-run triage. Never written by the CDC/sync pipeline
-- into acmeins, never replicated back to DB2. No row content and no PII: tables hold table names,
-- policy-type/branch dimensions, totals, counts and record keys only. Retention: until cutover
-- sign-off plus the rollback window, then dropped (FU-529-5). There is no DB2 counterpart; the
-- results always live in PostgreSQL, also when the reverse sync writes to DB2.

CREATE SCHEMA IF NOT EXISTS acmeins_shadow;

-- Which database is the PAS system of record. DB2 until the cutover gate; set to PG at cutover and
-- back to DB2 by a rollback. The forward sync refuses to run unless DB2 is the system of record,
-- the reverse sync unless PG is, so neither can overwrite writes made on the authoritative side.
CREATE TABLE acmeins_shadow.system_of_record (
    id                  smallint        NOT NULL DEFAULT 1,
    system_name         varchar(8)      NOT NULL,
    changed_at          timestamp(6)    NOT NULL DEFAULT localtimestamp,
    changed_by          varchar(64)     NOT NULL,
    reason              varchar(200)    NOT NULL,
    CONSTRAINT pk_system_of_record PRIMARY KEY (id),
    CONSTRAINT ck_system_of_record_single CHECK (id = 1),
    CONSTRAINT ck_system_of_record_name CHECK (system_name IN ('DB2', 'PG'))
);

INSERT INTO acmeins_shadow.system_of_record (id, system_name, changed_by, reason)
VALUES (1, 'DB2', 'flyway', 'AB-529 initial state: DB2 is the system of record until cutover');

-- One row per migration job run (bulk load, delta sync, reverse sync, reconciliation).
CREATE TABLE acmeins_shadow.migration_run (
    run_id              bigint GENERATED ALWAYS AS IDENTITY,
    job_name            varchar(64)     NOT NULL,
    job_execution_id    bigint,
    direction           varchar(16)     NOT NULL,
    business_date       date            NOT NULL,
    status              varchar(16)     NOT NULL,
    started_at          timestamp(6)    NOT NULL DEFAULT localtimestamp,
    finished_at         timestamp(6),
    -- Complete record-difference count of a reconciliation; recon_difference keeps at most 1,000
    -- keys per table, so the dashboard reads the total from here.
    record_differences  bigint,
    CONSTRAINT pk_migration_run PRIMARY KEY (run_id),
    CONSTRAINT ck_migration_run_direction CHECK (direction IN ('DB2_TO_PG', 'PG_TO_DB2', 'NONE')),
    CONSTRAINT ck_migration_run_status CHECK (status IN ('RUNNING', 'MATCH', 'DIFFERENCES', 'SYNCED', 'FAILED'))
);

CREATE INDEX ix_migration_run_date ON acmeins_shadow.migration_run (business_date, job_name);

-- Rows applied per table by a sync run.
CREATE TABLE acmeins_shadow.sync_table_result (
    run_id              bigint          NOT NULL,
    table_name          varchar(32)     NOT NULL,
    source_rows         bigint          NOT NULL,
    inserted            bigint          NOT NULL,
    updated             bigint          NOT NULL,
    deleted             bigint          NOT NULL,
    CONSTRAINT pk_sync_table_result PRIMARY KEY (run_id, table_name),
    CONSTRAINT fk_sync_table_result_run FOREIGN KEY (run_id) REFERENCES acmeins_shadow.migration_run (run_id)
);

-- One row per reconciliation check: a row count, a total for one policy type and branch, a hash
-- comparison or an orphan count. status MATCH or DIFF.
CREATE TABLE acmeins_shadow.recon_check (
    run_id              bigint          NOT NULL,
    check_id            varchar(160)    NOT NULL,
    kind                varchar(16)     NOT NULL,
    table_name          varchar(32)     NOT NULL,
    dimension           varchar(64)     NOT NULL,
    metric              varchar(32)     NOT NULL,
    db2_value           numeric(20,2),
    pg_value            numeric(20,2),
    status              char(5)         NOT NULL,
    CONSTRAINT pk_recon_check PRIMARY KEY (run_id, check_id),
    CONSTRAINT fk_recon_check_run FOREIGN KEY (run_id) REFERENCES acmeins_shadow.migration_run (run_id),
    CONSTRAINT ck_recon_check_kind CHECK (kind IN ('COUNT', 'TOTAL', 'HASH', 'ORPHAN')),
    CONSTRAINT ck_recon_check_status CHECK (status IN ('MATCH', 'DIFF'))
);

-- Record-level differences found by the hash comparison: the key only, never column values.
CREATE TABLE acmeins_shadow.recon_difference (
    run_id              bigint          NOT NULL,
    table_name          varchar(32)     NOT NULL,
    record_key          varchar(64)     NOT NULL,
    difference          varchar(20)     NOT NULL,
    CONSTRAINT pk_recon_difference PRIMARY KEY (run_id, table_name, record_key),
    CONSTRAINT fk_recon_difference_run FOREIGN KEY (run_id) REFERENCES acmeins_shadow.migration_run (run_id),
    CONSTRAINT ck_recon_difference CHECK (difference IN ('MISSING_IN_PG', 'MISSING_IN_DB2', 'HASH_MISMATCH', 'DUPLICATE_KEY'))
);

-- What the team looks at every morning (dashboard / report query).
CREATE VIEW acmeins_shadow.recon_daily_summary AS
SELECT r.business_date,
       r.run_id,
       r.job_name,
       r.status,
       r.finished_at,
       COUNT(c.check_id) AS checks,
       COALESCE(SUM(CASE WHEN c.status = 'DIFF' THEN 1 ELSE 0 END), 0) AS failed_checks,
       COALESCE(r.record_differences, 0) AS record_differences
  FROM acmeins_shadow.migration_run r
  LEFT JOIN acmeins_shadow.recon_check c ON c.run_id = r.run_id
 WHERE r.status IN ('MATCH', 'DIFFERENCES')
 GROUP BY r.business_date, r.run_id, r.job_name, r.status, r.finished_at, r.record_differences;

COMMENT ON SCHEMA acmeins_shadow IS 'AB-529 DS-02: migration sync runs, reconciliation results and parallel-run outputs; dropped after cutover sign-off plus the rollback window';
