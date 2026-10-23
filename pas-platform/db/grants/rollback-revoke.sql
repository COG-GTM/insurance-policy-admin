REVOKE INSERT, UPDATE, DELETE ON TABLE
    pas.policies, pas.coverages, pas.premiums, pas.endorsements,
    pas.underwriting_decisions, pas.policy_holders, pas.territory_factors
      FROM pas_service, pas_batch;
REVOKE USAGE, SELECT ON SEQUENCE pas.policy_seq, pas.underwriting_decision_seq
    FROM pas_service, pas_batch;
GRANT INSERT, UPDATE, DELETE ON TABLE
    pas.outbox, pas.idempotency_keys, pas.policy_audit, pas.policy_renewals
    TO pas_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas
    REVOKE INSERT, UPDATE, DELETE ON TABLES FROM pas_service, pas_batch;
