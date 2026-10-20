-- AB-528: legacy queue bridges (FileNet document trigger, underwriting queues), idempotent
-- consumers and dead-letter replay.

-- Policy row as committed at each policyVersion. Written in the same transaction as the event,
-- so a consumer can rebuild the 125-byte POLICY-RECORD for exactly the version an event names
-- (ADR 0001 D4) even after later mutations. Holds no policyholder PII (holder ID only).
CREATE TABLE pas.policy_versions (
    policy_number   VARCHAR(12)   NOT NULL,
    policy_version  BIGINT        NOT NULL,
    policy_type     CHAR(3)       NOT NULL,
    policy_status   CHAR(2)       NOT NULL,
    effective_date  DATE          NOT NULL,
    expiry_date     DATE          NOT NULL,
    policyholder_id CHAR(10)      NOT NULL,
    agent_code      CHAR(6),
    branch_code     CHAR(4),
    total_premium   NUMERIC(11,2),
    deductible      NUMERIC(9,2),
    coverage_limit  NUMERIC(13,2),
    inception_date  DATE,
    renewal_count   SMALLINT,
    uw_status       CHAR(2),
    risk_score      SMALLINT,
    web_indicator   CHAR(1),
    api_flag        CHAR(1),
    last_updated    TIMESTAMP(6)  NOT NULL,
    updated_by      CHAR(8)       NOT NULL,
    captured_at     TIMESTAMP(6)  NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT pk_policy_versions PRIMARY KEY (policy_number, policy_version)
);

-- Consumer idempotency: one row per (consumer, eventId) processed. Kafka delivery is
-- at-least-once and operators can replay dead letters, so every consumer claims the eventId
-- in the transaction that does its work.
CREATE TABLE pas.processed_events (
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    topic        VARCHAR(249) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id)
);

-- Records that exhausted their retries and landed on a <topic>.dlt topic, parked for an
-- operator to inspect and replay (POST /api/v1/admin/dead-letters/{id}/replay, batch:operate).
CREATE TABLE pas.dead_letters (
    dead_letter_id  BIGINT        GENERATED ALWAYS AS IDENTITY,
    dlt_topic       VARCHAR(249)  NOT NULL,
    dlt_partition   INTEGER       NOT NULL,
    dlt_offset      BIGINT        NOT NULL,
    original_topic  VARCHAR(249)  NOT NULL,
    consumer_group  VARCHAR(255),
    message_key     VARCHAR(255),
    event_id        VARCHAR(64),
    event_type      VARCHAR(100),
    payload         TEXT,
    error_class     VARCHAR(255),
    error_message   VARCHAR(1000),
    status          VARCHAR(20)   NOT NULL DEFAULT 'PARKED',
    recorded_at     TIMESTAMP(6)  NOT NULL DEFAULT LOCALTIMESTAMP,
    replay_count    INTEGER       NOT NULL DEFAULT 0,
    replayed_at     TIMESTAMP(6),
    replayed_by     VARCHAR(255),
    CONSTRAINT pk_dead_letters PRIMARY KEY (dead_letter_id),
    CONSTRAINT uq_dead_letters_position UNIQUE (dlt_topic, dlt_partition, dlt_offset),
    CONSTRAINT ck_dead_letters_status CHECK (status IN ('PARKED', 'REPLAYED'))
);

CREATE INDEX ix_dead_letters_status ON pas.dead_letters (status, dead_letter_id);
