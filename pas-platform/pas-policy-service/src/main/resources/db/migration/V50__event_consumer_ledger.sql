CREATE TABLE pas.consumed_events (
    consumer_group VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL,
    topic VARCHAR(120) NOT NULL,
    policy_number VARCHAR(40) NOT NULL,
    policy_version BIGINT NOT NULL,
    result VARCHAR(20) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE pas.consumer_policy_versions (
    consumer_group VARCHAR(120) NOT NULL,
    policy_number VARCHAR(40) NOT NULL,
    last_policy_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, policy_number)
);
