CREATE TABLE pas_batch.recon_run (
    id BIGSERIAL PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL CHECK (status IN ('CLEAN', 'DIFFERENCES', 'FAILED')),
    counts JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE pas_batch.recon_difference (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES pas_batch.recon_run(id),
    table_name VARCHAR(32) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (
        kind IN ('COUNT', 'TOTAL', 'HASH', 'MISSING_IN_TARGET', 'MISSING_IN_SOURCE')
    ),
    record_key VARCHAR(128),
    dimension VARCHAR(128),
    source_value TEXT,
    target_value TEXT
);

CREATE INDEX ix_recon_difference_run ON pas_batch.recon_difference (run_id, table_name, kind);
