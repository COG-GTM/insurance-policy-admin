CREATE TABLE pas_batch.parallel_run_cycle (
    cycle_id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'CLEAN', 'DIFFERENCES')),
    CHECK (end_date >= start_date)
);

CREATE TABLE pas_batch.parallel_run_date (
    cycle_id UUID NOT NULL REFERENCES pas_batch.parallel_run_cycle(cycle_id),
    business_date DATE NOT NULL,
    last_run_at TIMESTAMPTZ NOT NULL,
    counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (cycle_id, business_date)
);

CREATE TABLE pas_batch.parallel_run_difference (
    id BIGSERIAL PRIMARY KEY,
    cycle_id UUID REFERENCES pas_batch.parallel_run_cycle(cycle_id),
    business_date DATE NOT NULL,
    artifact VARCHAR(16) NOT NULL CHECK (artifact IN ('PREMIUMS', 'EXTRACT')),
    record_key VARCHAR(256) NOT NULL,
    field VARCHAR(256) NOT NULL,
    legacy_value TEXT,
    shadow_value TEXT,
    present BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_run TIMESTAMPTZ NOT NULL,
    last_seen_run TIMESTAMPTZ NOT NULL,
    classification VARCHAR(16) NOT NULL DEFAULT 'UNTRIAGED'
        CHECK (classification IN ('UNTRIAGED', 'BUG', 'APPROVED_FIX', 'DATA_ISSUE')),
    quirk_id VARCHAR(8),
    triage_note TEXT,
    triaged_by VARCHAR(200),
    triaged_at TIMESTAMPTZ,
    UNIQUE (business_date, artifact, record_key, field)
);

CREATE INDEX ix_parallel_run_diff_date ON pas_batch.parallel_run_difference
    (business_date, classification) WHERE present;

CREATE TABLE pas_batch.parallel_run_triage_log (
    id BIGSERIAL PRIMARY KEY,
    difference_id BIGINT NOT NULL
        REFERENCES pas_batch.parallel_run_difference(id),
    classification VARCHAR(16) NOT NULL,
    quirk_id VARCHAR(8),
    triage_note TEXT,
    triaged_by VARCHAR(200) NOT NULL,
    triaged_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
