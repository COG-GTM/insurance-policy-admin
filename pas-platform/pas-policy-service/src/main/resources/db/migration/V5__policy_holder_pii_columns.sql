-- AB-521: Encrypted replacements for regulated fields omitted from V2 per ADR D7.
ALTER TABLE pas.policy_holders
    ADD COLUMN date_of_birth_enc BYTEA,
    ADD COLUMN ssn_last4_enc BYTEA,
    ADD COLUMN tax_id_enc BYTEA,
    ADD COLUMN credit_score_enc BYTEA,
    ADD COLUMN tax_id_bidx BYTEA,
    ADD CONSTRAINT ck_ph_tax_id_bidx_length
        CHECK (tax_id_bidx IS NULL OR octet_length(tax_id_bidx) = 32);

CREATE INDEX ix_ph_tax_id_bidx ON pas.policy_holders (tax_id_bidx);

COMMENT ON COLUMN pas.policy_holders.date_of_birth_enc IS 'PiiCipher v1 envelope: P, version, key id, key version, IV, AES-GCM ciphertext and tag.';
COMMENT ON COLUMN pas.policy_holders.ssn_last4_enc IS 'PiiCipher v1 envelope; AES-256-GCM with row/column AAD.';
COMMENT ON COLUMN pas.policy_holders.tax_id_enc IS 'PiiCipher v1 envelope; AES-256-GCM with row/column AAD.';
COMMENT ON COLUMN pas.policy_holders.credit_score_enc IS 'PiiCipher v1 envelope; AES-256-GCM with row/column AAD.';
COMMENT ON COLUMN pas.policy_holders.tax_id_bidx IS '32-byte HMAC-SHA256 exact-match index using a separate key.';
