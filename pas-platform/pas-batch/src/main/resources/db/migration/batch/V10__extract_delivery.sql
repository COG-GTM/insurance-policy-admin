CREATE TABLE pas_batch.extract_delivery (
    delivery_id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    job_execution_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    target VARCHAR(32) NOT NULL,
    remote_path VARCHAR(512) NOT NULL,
    layout_version VARCHAR(64) NOT NULL,
    record_count BIGINT NOT NULL,
    byte_count BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CONSTRAINT ck_extract_delivery_status CHECK (status IN ('DELIVERED', 'FAILED')),
    attempts INTEGER NOT NULL,
    failure_type VARCHAR(200),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_extract_delivery UNIQUE (job_execution_id, remote_path)
);

CREATE INDEX ix_extract_delivery_job_date ON pas_batch.extract_delivery (job_name, business_date);
