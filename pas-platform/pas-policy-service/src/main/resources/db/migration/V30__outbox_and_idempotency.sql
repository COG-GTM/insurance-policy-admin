CREATE TABLE pas.outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(40) NOT NULL,
    topic VARCHAR(120) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_unpublished_aggregate
    ON pas.outbox (aggregate_id, id) WHERE published_at IS NULL;

CREATE TABLE pas.idempotency_keys (
    caller_sub VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INTEGER,
    response_location VARCHAR(255),
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (caller_sub, idempotency_key)
);
