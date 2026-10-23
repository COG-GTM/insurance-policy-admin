ALTER TABLE pas.underwriting_decisions
    ADD COLUMN source_system VARCHAR(8);

CREATE UNIQUE INDEX ux_uwd_db2_source
    ON pas.underwriting_decisions (policy_number, decision_date)
    WHERE source_system = 'DB2';
