-- AB-521: regulated POLICY_HOLDERS columns (DOB, SSN last 4, tax ID, credit score).
-- Values are encrypted in the application with AES-256-GCM (PiiAttributeConverter) and stored as
-- Base64 text; the database never sees plaintext. *_bidx columns hold a deterministic HMAC-SHA256
-- blind index (64 hex chars) for equality lookup. Mapping from DB2: DATE_OF_BIRTH -> date_of_birth_enc,
-- SSN_LAST4 -> ssn_last4_enc (+ ssn_last4_bidx), TAX_ID -> tax_id_enc (+ tax_id_bidx),
-- CREDIT_SCORE -> credit_score_enc. See docs/modernization/pii-handling.md.

ALTER TABLE pas.policy_holders
    ADD COLUMN date_of_birth_enc VARCHAR(255),
    ADD COLUMN ssn_last4_enc     VARCHAR(255),
    ADD COLUMN ssn_last4_bidx    CHAR(64),
    ADD COLUMN tax_id_enc        VARCHAR(255),
    ADD COLUMN tax_id_bidx       CHAR(64),
    ADD COLUMN credit_score_enc  VARCHAR(255);

CREATE INDEX ix_ph_tax_id_bidx ON pas.policy_holders (tax_id_bidx);
CREATE INDEX ix_ph_ssn_last4_bidx ON pas.policy_holders (ssn_last4_bidx);

COMMENT ON COLUMN pas.policy_holders.date_of_birth_enc IS 'PII: AES-256-GCM ciphertext of DATE_OF_BIRTH (ISO yyyy-MM-dd)';
COMMENT ON COLUMN pas.policy_holders.ssn_last4_enc IS 'PII: AES-256-GCM ciphertext of SSN_LAST4';
COMMENT ON COLUMN pas.policy_holders.ssn_last4_bidx IS 'PII: HMAC-SHA256 blind index of SSN_LAST4';
COMMENT ON COLUMN pas.policy_holders.tax_id_enc IS 'PII: AES-256-GCM ciphertext of TAX_ID';
COMMENT ON COLUMN pas.policy_holders.tax_id_bidx IS 'PII: HMAC-SHA256 blind index of TAX_ID';
COMMENT ON COLUMN pas.policy_holders.credit_score_enc IS 'PII: AES-256-GCM ciphertext of CREDIT_SCORE';
