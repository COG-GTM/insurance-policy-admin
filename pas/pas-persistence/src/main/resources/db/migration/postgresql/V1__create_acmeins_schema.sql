-- AB-519: baseline for the PostgreSQL target (ADR 0001 D3).
-- Creates the empty target schema. The ACMEINS tables arrive in AB-521 (V10-V19);
-- schema pasapp (outbox, audit) arrives in AB-525 (V50-V59).
CREATE SCHEMA IF NOT EXISTS acmeins;

COMMENT ON SCHEMA acmeins IS 'PAS target system of record (ADR 0001 D3); CDC replica of DB2 ACMEINS until cutover';
