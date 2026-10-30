------------------------------------------------------------------------
-- Policy Administration System (PAS) - DB2 v12 for z/OS
-- Migration: allow more than one underwriting decision per policy/day
-- Date:      2026-10-30
--
-- UNDWRT (PUWR) now numbers each decision of the day in DECISION_SEQ.
-- The old key (POLICY_NUMBER, DECISION_DATE) rejected every second
-- same-day decision with SQLCODE -803. Existing rows get DECISION_SEQ 1.
--
-- The table is rebuilt rather than altered: DROP PRIMARY KEY keeps the
-- old system-named unique index, which would still reject a second
-- same-day decision. Dropping the old table removes that index.
--
-- The new table is created without named constraints so it can coexist
-- with the old one; PK_UW_DECISIONS and FK_UWD_POLICY are added after
-- the old table is gone.
--
-- Run before deploying the new UNDWRT load module, with PUWR disabled.
-- Re-apply the table's GRANTs afterwards (DROP TABLE removes them). If
-- step 4 leaves the table space CHECK-pending, run CHECK DATA.
------------------------------------------------------------------------

-- 1. New table and the unique index for the new key
CREATE TABLE ACMEINS.UNDERWRITING_DECISIONS_NEW (
    POLICY_NUMBER       CHAR(12)        NOT NULL,
    DECISION_DATE       DATE            NOT NULL,
    DECISION_SEQ        SMALLINT        NOT NULL DEFAULT 1,
    DECISION_CODE       CHAR(2)         NOT NULL,
    RISK_SCORE          SMALLINT,
    DECISION_REASON     VARCHAR(100),
    UNDERWRITER_ID      CHAR(8),
    OVERRIDE_REASON     VARCHAR(200),
    OVERRIDE_BY         CHAR(8),
    CREATED_TIMESTAMP   TIMESTAMP       NOT NULL DEFAULT CURRENT TIMESTAMP
) IN ACMEDB.PASTS01;

CREATE UNIQUE INDEX ACMEINS.XUWD_PK
    ON ACMEINS.UNDERWRITING_DECISIONS_NEW
    (POLICY_NUMBER, DECISION_DATE, DECISION_SEQ)
    USING STOGROUP ACMESG;

-- 2. Copy existing decisions (one per policy/day, so DECISION_SEQ 1)
INSERT INTO ACMEINS.UNDERWRITING_DECISIONS_NEW
    (POLICY_NUMBER, DECISION_DATE, DECISION_SEQ, DECISION_CODE,
     RISK_SCORE, DECISION_REASON, UNDERWRITER_ID, OVERRIDE_REASON,
     OVERRIDE_BY, CREATED_TIMESTAMP)
SELECT POLICY_NUMBER, DECISION_DATE, 1, DECISION_CODE,
       RISK_SCORE, DECISION_REASON, UNDERWRITER_ID, OVERRIDE_REASON,
       OVERRIDE_BY, CREATED_TIMESTAMP
  FROM ACMEINS.UNDERWRITING_DECISIONS;

-- 3. Drop the old table (and its old-key unique index), take its name
DROP TABLE ACMEINS.UNDERWRITING_DECISIONS;

RENAME TABLE ACMEINS.UNDERWRITING_DECISIONS_NEW
    TO UNDERWRITING_DECISIONS;

-- 4. Original constraint names on the new key (PK uses XUWD_PK)
ALTER TABLE ACMEINS.UNDERWRITING_DECISIONS
    ADD CONSTRAINT PK_UW_DECISIONS PRIMARY KEY
        (POLICY_NUMBER, DECISION_DATE, DECISION_SEQ);

ALTER TABLE ACMEINS.UNDERWRITING_DECISIONS
    ADD CONSTRAINT FK_UWD_POLICY FOREIGN KEY (POLICY_NUMBER)
        REFERENCES ACMEINS.POLICIES (POLICY_NUMBER);

COMMIT;
