------------------------------------------------------------------------
-- Policy Administration System (PAS) - DB2 v12 for z/OS
-- Migration: allow more than one underwriting decision per policy/day
-- Date:      2026-10-30
--
-- UNDWRT (PUWR) now numbers each decision of the day in DECISION_SEQ.
-- The old key (POLICY_NUMBER, DECISION_DATE) rejected every second
-- same-day decision with SQLCODE -803. Existing rows get DECISION_SEQ 1.
--
-- Run before deploying the new UNDWRT load module. Before step 3, find
-- the unique index that enforced the old key:
--   SELECT CREATOR, NAME FROM SYSIBM.SYSINDEXES
--    WHERE TBCREATOR = 'ACMEINS'
--      AND TBNAME = 'UNDERWRITING_DECISIONS'
--      AND UNIQUERULE = 'P';
-- REORG the table space after step 1 (advisory REORG-pending).
------------------------------------------------------------------------

-- 1. New key column
ALTER TABLE ACMEINS.UNDERWRITING_DECISIONS
    ADD COLUMN DECISION_SEQ SMALLINT NOT NULL WITH DEFAULT 1;

-- 2. Release the old primary key
ALTER TABLE ACMEINS.UNDERWRITING_DECISIONS
    DROP PRIMARY KEY;

-- 3. Drop the old (POLICY_NUMBER, DECISION_DATE) unique index found by
--    the catalog query above, e.g.:
-- DROP INDEX ACMEINS.<old-pk-index>;

-- 4. Enforcing index and primary key on the new key
CREATE UNIQUE INDEX ACMEINS.XUWD_PK
    ON ACMEINS.UNDERWRITING_DECISIONS
    (POLICY_NUMBER, DECISION_DATE, DECISION_SEQ)
    USING STOGROUP ACMESG;

ALTER TABLE ACMEINS.UNDERWRITING_DECISIONS
    ADD CONSTRAINT PK_UW_DECISIONS PRIMARY KEY
        (POLICY_NUMBER, DECISION_DATE, DECISION_SEQ);
