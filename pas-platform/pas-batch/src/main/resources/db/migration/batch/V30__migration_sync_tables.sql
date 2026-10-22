CREATE TABLE pas_batch.sync_watermark (
    table_name VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('FORWARD', 'REVERSE')),
    watermark_value TIMESTAMP,
    synced_through TIMESTAMPTZ,
    coverage_cursor VARCHAR(12),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (table_name, direction)
);
