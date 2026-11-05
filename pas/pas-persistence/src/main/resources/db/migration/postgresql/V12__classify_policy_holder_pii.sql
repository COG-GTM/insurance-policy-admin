-- AB-521: personal data in POLICY_HOLDERS (ADR 0001 D9, docs/pas/data-model.md section 5).
-- 1. Every personal column carries its classification as a column comment, so data catalogues
--    and reviews can find it.
-- 2. policy_holders_masked is the only object reporting, support and non-production readers get.
--    It never returns RESTRICTED values and masks PERSONAL values. Grants are applied per
--    environment by the DBA team (roles are not created by migrations).
-- Column-level encryption or tokenisation of ssn_last4 and tax_id is pending the Security
-- decision recorded in docs/pas/data-model.md; it lands as a later migration in this range.

COMMENT ON COLUMN acmeins.policy_holders.last_name IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.first_name IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.company_name IS 'PII: PERSONAL; may hold a sole trader''s own name';
COMMENT ON COLUMN acmeins.policy_holders.middle_init IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.addr_line1 IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.addr_line2 IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.city IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.zip_code IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.phone IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.email IS 'PII: PERSONAL';
COMMENT ON COLUMN acmeins.policy_holders.date_of_birth IS 'PII: RESTRICTED';
COMMENT ON COLUMN acmeins.policy_holders.ssn_last4 IS 'PII: RESTRICTED; column-level encryption or tokenisation required (ADR 0001 D9)';
COMMENT ON COLUMN acmeins.policy_holders.tax_id IS 'PII: RESTRICTED; column-level encryption or tokenisation required (ADR 0001 D9)';
COMMENT ON COLUMN acmeins.policy_holders.credit_score IS 'PII: RESTRICTED';

CREATE VIEW acmeins.policy_holders_masked AS
SELECT
    cust_id,
    cust_type,
    CASE WHEN last_name IS NULL THEN NULL ELSE left(last_name, 1) || '***' END AS last_name,
    CASE WHEN first_name IS NULL THEN NULL ELSE left(first_name, 1) || '***' END AS first_name,
    CASE WHEN company_name IS NULL THEN NULL ELSE left(company_name, 1) || '***' END AS company_name,
    state_code,
    country_code,
    CASE WHEN email IS NULL THEN NULL ELSE '***@' || split_part(email, '@', 2) END AS email,
    CAST(extract(year FROM date_of_birth) AS integer) AS birth_year,
    CASE WHEN ssn_last4 IS NULL THEN NULL ELSE '****' END AS ssn_last4,
    CASE WHEN tax_id IS NULL THEN NULL ELSE '**********' END AS tax_id,
    risk_tier,
    gdpr_consent,
    created_date,
    last_updated
FROM acmeins.policy_holders;

COMMENT ON VIEW acmeins.policy_holders_masked IS 'Masked POLICY_HOLDERS for reporting, support and non-production use; no RESTRICTED values';
