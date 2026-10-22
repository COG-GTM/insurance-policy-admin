GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    pas.policies, pas.coverages, pas.premiums, pas.endorsements,
    pas.underwriting_decisions, pas.policy_holders, pas.territory_factors
    TO pas_service, pas_batch;
GRANT SELECT ON ALL TABLES IN SCHEMA pas TO pas_readonly, pas_sync;

REVOKE INSERT, UPDATE, DELETE ON TABLE
    pas.policies, pas.coverages, pas.premiums, pas.endorsements,
    pas.underwriting_decisions, pas.policy_holders, pas.territory_factors
    FROM pas_sync;
GRANT USAGE, SELECT ON SEQUENCE pas.policy_seq, pas.underwriting_decision_seq
    TO pas_service, pas_batch;
REVOKE USAGE, SELECT ON SEQUENCE pas.policy_seq, pas.underwriting_decision_seq
    FROM pas_sync;

ALTER DEFAULT PRIVILEGES IN SCHEMA pas
    GRANT SELECT ON TABLES TO pas_readonly, pas_sync;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pas_service, pas_batch;
