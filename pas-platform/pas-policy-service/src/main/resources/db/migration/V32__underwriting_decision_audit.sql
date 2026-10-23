CREATE SEQUENCE pas.underwriting_decision_seq;

ALTER TABLE pas.underwriting_decisions
    ADD COLUMN decision_id BIGINT;

UPDATE pas.underwriting_decisions
SET decision_id = nextval('pas.underwriting_decision_seq');

ALTER TABLE pas.underwriting_decisions
    ALTER COLUMN decision_id SET DEFAULT nextval('pas.underwriting_decision_seq'),
    ALTER COLUMN decision_id SET NOT NULL;

ALTER TABLE pas.underwriting_decisions
    DROP CONSTRAINT pk_uw_decisions;

ALTER TABLE pas.underwriting_decisions
    ADD CONSTRAINT pk_uw_decisions PRIMARY KEY (decision_id);

CREATE INDEX ix_uwd_policy_date
    ON pas.underwriting_decisions (policy_number, decision_date);

ALTER TABLE pas.underwriting_decisions
    ADD COLUMN decided_by VARCHAR(255),
    ADD COLUMN decision_reasons TEXT[],
    ADD COLUMN overrides_decision_id BIGINT
        REFERENCES pas.underwriting_decisions (decision_id);

COMMENT ON TABLE pas.underwriting_decisions IS
    'Decision audit rows have unique identifiers; same-day collisions are fixed by AB-525 (Q-25).';
COMMENT ON COLUMN pas.underwriting_decisions.decision_id IS
    'Stable audit-row identifier added by AB-525 to permit multiple decisions per policy per day (Q-25).';
