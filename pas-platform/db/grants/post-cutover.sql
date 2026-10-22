GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    pas.policies, pas.coverages, pas.premiums, pas.endorsements,
    pas.underwriting_decisions, pas.policy_holders, pas.territory_factors
    TO pas_service, pas_batch;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    pas.consumed_events, pas.consumer_policy_versions
    TO pas_service;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    pas.outbox, pas.idempotency_keys, pas.policy_audit, pas.policy_renewals
    TO pas_service;
GRANT INSERT ON TABLE pas.change_log TO pas_service, pas_batch;
GRANT USAGE, SELECT ON SEQUENCE pas.change_log_id_seq TO pas_service, pas_batch;
GRANT SELECT ON ALL TABLES IN SCHEMA pas TO pas_readonly, pas_sync, pas_service, pas_batch;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pas_batch TO pas_batch;

REVOKE INSERT, UPDATE, DELETE ON TABLE
    pas.policies, pas.coverages, pas.premiums, pas.endorsements,
    pas.underwriting_decisions, pas.policy_holders, pas.territory_factors
    FROM pas_sync;
GRANT USAGE, SELECT ON SEQUENCE pas.policy_seq, pas.underwriting_decision_seq
    TO pas_service, pas_batch;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA pas
    TO pas_service, pas_batch;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA pas_shadow
    TO pas_service, pas_batch;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA pas_batch TO pas_batch;
REVOKE USAGE, SELECT ON SEQUENCE pas.policy_seq, pas.underwriting_decision_seq
    FROM pas_sync;
REVOKE INSERT ON TABLE pas.change_log FROM pas_sync;
REVOKE USAGE, SELECT ON SEQUENCE pas.change_log_id_seq FROM pas_sync;

ALTER DEFAULT PRIVILEGES IN SCHEMA pas
    GRANT SELECT ON TABLES TO pas_readonly, pas_sync;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pas_service, pas_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas
    GRANT USAGE, SELECT ON SEQUENCES TO pas_service, pas_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas_batch
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pas_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas_batch
    GRANT USAGE, SELECT ON SEQUENCES TO pas_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas_shadow
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pas_service, pas_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas_shadow
    GRANT USAGE, SELECT ON SEQUENCES TO pas_service, pas_batch;
