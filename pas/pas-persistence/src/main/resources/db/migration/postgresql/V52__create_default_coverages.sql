-- AB-525: default coverages per policy type, table-driven (quirk Q-10). POLNEW 6000-INSERT-COVERAGES
-- hardcodes these codes and descriptions ("TODO: This should be table-driven"); the seed rows below
-- are exactly those values, so new policies get the same coverages. LIF, HLT and any other type get
-- none, as today (product confirmation pending). Sequence numbers start at 1 (the COBOL never
-- initialises COV-SEQUENCE-NUM). DB2 gets the same table in PASAPP (db2/V52).

CREATE SCHEMA IF NOT EXISTS pasapp;

CREATE TABLE pasapp.default_coverages (
    policy_type         char(3)         NOT NULL,
    coverage_seq        smallint        NOT NULL,
    coverage_type       char(4)         NOT NULL,
    description         varchar(40)     NOT NULL,
    CONSTRAINT pk_default_coverages PRIMARY KEY (policy_type, coverage_seq),
    CONSTRAINT ck_default_cov_seq CHECK (coverage_seq > 0)
);

INSERT INTO pasapp.default_coverages (policy_type, coverage_seq, coverage_type, description) VALUES
    ('AUT', 1, 'AUTL', 'Auto Liability'),
    ('AUT', 2, 'AUTP', 'Auto Physical Damage'),
    ('HOM', 1, 'PROP', 'Dwelling Coverage'),
    ('HOM', 2, 'LIAB', 'Personal Liability'),
    ('COM', 1, 'PROP', 'Commercial Property'),
    ('COM', 2, 'LIAB', 'General Liability'),
    ('COM', 3, 'WKCP', 'Workers Compensation');

COMMENT ON TABLE pasapp.default_coverages IS 'Coverages POLNEW creates per policy type (Q-10); seeded with the COBOL values';
