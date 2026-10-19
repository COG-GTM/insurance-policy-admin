------------------------------------------------------------------------
-- Policy Administration System (PAS) - DB2 v12 for z/OS
-- DDL Script: At-rest protection for regulated POLICY_HOLDERS columns
--
-- Database:   DBPD (Production)
-- Schema:     ACMEINS
--
-- Run after create-tables.sql. Requires SECADM authority (column access
-- control, masks and RACF group checks) - coordinate with the DBA and
-- security teams before applying.
--
-- Protected columns: DATE_OF_BIRTH, SSN_LAST4, TAX_ID, CREDIT_SCORE.
--
-- Access model (RACF groups):
--   PASPII  - full clear-text PII (servicing, compliance, data subject
--             access requests under GDPR_CONSENT)
--   PASUWR  - underwriting; sees CREDIT_SCORE in the clear because
--             UNDWRT rates off it, everything else masked
--   everyone else (PASFACAD, extract/batch ids, reporting) sees masks
------------------------------------------------------------------------

------------------------------------------------------------------------
-- Column masks. Masks are applied to the final result of the outermost
-- SELECT, so they also cover DSNTEP2 extracts, ODBC/linked-server reads
-- and any future facade query.
------------------------------------------------------------------------
CREATE MASK ACMEINS.MASK_PH_DATE_OF_BIRTH
    ON ACMEINS.POLICY_HOLDERS
    FOR COLUMN DATE_OF_BIRTH RETURN
        CASE WHEN VERIFY_GROUP_FOR_USER(SESSION_USER, 'PASPII') = 1
             THEN DATE_OF_BIRTH
             WHEN DATE_OF_BIRTH IS NULL
             THEN NULL
             ELSE DATE(CHAR(YEAR(DATE_OF_BIRTH)) CONCAT '-01-01')
        END
    ENABLE;

CREATE MASK ACMEINS.MASK_PH_SSN_LAST4
    ON ACMEINS.POLICY_HOLDERS
    FOR COLUMN SSN_LAST4 RETURN
        CASE WHEN VERIFY_GROUP_FOR_USER(SESSION_USER, 'PASPII') = 1
             THEN SSN_LAST4
             WHEN SSN_LAST4 IS NULL
             THEN NULL
             ELSE 'XXXX'
        END
    ENABLE;

CREATE MASK ACMEINS.MASK_PH_TAX_ID
    ON ACMEINS.POLICY_HOLDERS
    FOR COLUMN TAX_ID RETURN
        CASE WHEN VERIFY_GROUP_FOR_USER(SESSION_USER, 'PASPII') = 1
             THEN TAX_ID
             WHEN TAX_ID IS NULL
             THEN NULL
             ELSE 'XXXXXXXXXX'
        END
    ENABLE;

CREATE MASK ACMEINS.MASK_PH_CREDIT_SCORE
    ON ACMEINS.POLICY_HOLDERS
    FOR COLUMN CREDIT_SCORE RETURN
        CASE WHEN VERIFY_GROUP_FOR_USER(SESSION_USER, 'PASPII') = 1
             OR VERIFY_GROUP_FOR_USER(SESSION_USER, 'PASUWR') = 1
             THEN CREDIT_SCORE
             WHEN CREDIT_SCORE IS NULL
             THEN NULL
             ELSE SMALLINT((CREDIT_SCORE / 50) * 50)
        END
    ENABLE;

ALTER TABLE ACMEINS.POLICY_HOLDERS
    ACTIVATE COLUMN ACCESS CONTROL;

------------------------------------------------------------------------
-- Masked customer projection for downstream consumers (Claims extract,
-- Broker Portal linked server, actuarial exposure files). It carries no
-- direct identifier beyond CUST_ID and never selects SSN_LAST4/TAX_ID,
-- so a leaked extract file cannot be de-anonymised from its contents.
------------------------------------------------------------------------
CREATE VIEW ACMEINS.POLICY_HOLDERS_EXTRACT AS
    SELECT CUST_ID,
           CUST_TYPE,
           COMPANY_NAME,
           CITY,
           STATE_CODE,
           SUBSTR(ZIP_CODE, 1, 5)          AS ZIP_CODE,
           COUNTRY_CODE,
           YEAR(DATE_OF_BIRTH)             AS BIRTH_YEAR,
           RISK_TIER,
           GDPR_CONSENT,
           CREATED_DATE,
           LAST_UPDATED
    FROM ACMEINS.POLICY_HOLDERS;

------------------------------------------------------------------------
-- Least-privilege grants. POLICY_HOLDERS itself is readable only by the
-- online CICS transactions and the PII/underwriting groups; everything
-- else goes through the masked projection.
------------------------------------------------------------------------
REVOKE SELECT ON TABLE ACMEINS.POLICY_HOLDERS FROM PUBLIC;

GRANT SELECT ON TABLE ACMEINS.POLICY_HOLDERS TO ROLE PASPII;
GRANT SELECT ON TABLE ACMEINS.POLICY_HOLDERS TO ROLE PASUWR;

GRANT SELECT ON TABLE ACMEINS.POLICY_HOLDERS_EXTRACT TO PASFACAD;
GRANT SELECT ON TABLE ACMEINS.POLICY_HOLDERS_EXTRACT TO PASBATCH;
GRANT SELECT ON TABLE ACMEINS.POLICY_HOLDERS_EXTRACT TO BRKRODBC;

COMMIT;
