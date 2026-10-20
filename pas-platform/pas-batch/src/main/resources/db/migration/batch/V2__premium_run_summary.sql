-- AB-523: run summary of premiumRecalculationJob (replaces the PREMBAT PREMRPT totals as the
-- queryable record of each run). One row per job execution; restarts of the same run date share
-- job_instance_id and carry cumulative totals.
CREATE TABLE pas_batch.premium_run_summary (
    job_execution_id BIGINT        NOT NULL,
    job_instance_id  BIGINT        NOT NULL,
    run_date         DATE          NOT NULL,
    batch_status     VARCHAR(10)   NOT NULL,
    exit_code        VARCHAR(2500) NOT NULL,
    outcome          VARCHAR(32)   NOT NULL,
    policies_read    BIGINT        NOT NULL,
    policies_updated BIGINT        NOT NULL,
    policy_errors    BIGINT        NOT NULL,
    rating_version   VARCHAR(64),
    report_path      VARCHAR(1024),
    started_at       TIMESTAMP,
    ended_at         TIMESTAMP,
    failure_message  VARCHAR(2500),
    recorded_at      TIMESTAMP     NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT pk_premium_run_summary PRIMARY KEY (job_execution_id),
    CONSTRAINT fk_premium_run_summary_execution FOREIGN KEY (job_execution_id)
        REFERENCES pas_batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE INDEX ix_premium_run_summary_run_date ON pas_batch.premium_run_summary (run_date);
