GRANT USAGE ON SCHEMA pas, pas_batch, pas_shadow
    TO pas_owner, pas_service, pas_batch, pas_sync, pas_readonly;

GRANT SELECT ON ALL TABLES IN SCHEMA pas
    TO pas_service, pas_batch, pas_sync, pas_readonly;

GRANT INSERT, UPDATE, DELETE ON TABLE
    pas.policies, pas.coverages, pas.premiums, pas.endorsements,
    pas.underwriting_decisions, pas.policy_holders, pas.territory_factors
    TO pas_sync;

GRANT INSERT, UPDATE, DELETE ON TABLE
    pas.outbox, pas.idempotency_keys, pas.policy_audit, pas.policy_renewals
    TO pas_service;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pas_shadow
    TO pas_service, pas_batch;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pas_batch
    TO pas_batch;

GRANT USAGE, SELECT ON SEQUENCE pas.policy_seq, pas.underwriting_decision_seq
    TO pas_sync;

ALTER DEFAULT PRIVILEGES IN SCHEMA pas
    GRANT SELECT ON TABLES TO pas_readonly, pas_service, pas_batch, pas_sync;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas_shadow
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pas_service, pas_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas_batch
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pas_batch;
