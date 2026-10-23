CREATE TABLE pas_batch.premium_run_summary (
    job_execution_id BIGINT PRIMARY KEY,
    job_instance_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    write_target VARCHAR(16) NOT NULL CHECK (write_target IN ('PAS', 'SHADOW')),
    status VARCHAR(32) NOT NULL,
    exit_code VARCHAR(64) NOT NULL,
    policies_read BIGINT NOT NULL,
    policies_updated BIGINT NOT NULL,
    policies_skipped BIGINT NOT NULL,
    policies_error BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX ix_premium_run_summary_date
    ON pas_batch.premium_run_summary (business_date);

CREATE TABLE pas_batch.premium_run_item (
    job_instance_id BIGINT NOT NULL,
    policy_number CHAR(12) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPDATED', 'ERROR')),
    total_premium NUMERIC(11,2),
    error_type VARCHAR(200),
    PRIMARY KEY (job_instance_id, policy_number)
);
