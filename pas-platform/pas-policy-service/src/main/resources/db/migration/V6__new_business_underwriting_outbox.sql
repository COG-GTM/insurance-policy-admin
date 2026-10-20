-- AB-525: first write path (POLNEW new business, UNDWRT underwriting decisions).

-- Optimistic-lock version of the committed policy state. Events carry it as policyVersion
-- (ADR 0001 D4 envelope); migrated rows start at 0.
ALTER TABLE pas.policies ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Manual overrides record the caller's JWT subject (OIDC `sub`, up to 255 characters). DB2 had
-- CHAR(8) and only ever wrote 'SYSTEM' here (quirk register Q-21), so widening loses nothing.
ALTER TABLE pas.underwriting_decisions ALTER COLUMN underwriter_id TYPE VARCHAR(255);
ALTER TABLE pas.underwriting_decisions ALTER COLUMN override_by TYPE VARCHAR(255);

-- Transactional outbox (ADR 0001 D4): written in the same transaction as the state change,
-- published to Kafka by OutboxRelay. payload is the exact JSON envelope sent as the record value.
CREATE TABLE pas.outbox (
    outbox_id    BIGINT        GENERATED ALWAYS AS IDENTITY,
    event_id     UUID          NOT NULL,
    topic        VARCHAR(249)  NOT NULL,
    message_key  VARCHAR(255)  NOT NULL,
    event_type   VARCHAR(100)  NOT NULL,
    payload      TEXT          NOT NULL,
    created_at   TIMESTAMP(6)  NOT NULL DEFAULT LOCALTIMESTAMP,
    published_at TIMESTAMP(6),
    attempts     INTEGER       NOT NULL DEFAULT 0,
    last_error   VARCHAR(1000),
    CONSTRAINT pk_outbox PRIMARY KEY (outbox_id),
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX ix_outbox_pending ON pas.outbox (outbox_id) WHERE published_at IS NULL;

-- Idempotency-Key replay for POST /api/v1/policies, scoped to the calling subject.
CREATE TABLE pas.idempotency_keys (
    caller_subject  VARCHAR(255) NOT NULL,
    operation       VARCHAR(40)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    policy_number   CHAR(12)     NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (caller_subject, operation, idempotency_key)
);

COMMENT ON COLUMN pas.policies.version IS 'Optimistic-lock version; events carry it as policyVersion';
COMMENT ON TABLE pas.outbox IS 'Transactional outbox for pas.*.events.v1 topics (ADR 0001 D4)';
COMMENT ON TABLE pas.idempotency_keys IS 'Idempotency-Key replay records for write APIs';
