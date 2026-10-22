CREATE TABLE pas_batch.sync_watermark (
    table_name VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('FORWARD', 'REVERSE')),
    watermark_value TIMESTAMP,
    synced_through TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_name, direction)
);

CREATE TABLE pas_batch.reverse_sync_policy_version (
    policy_number CHAR(12) PRIMARY KEY,
    row_version BIGINT NOT NULL,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
