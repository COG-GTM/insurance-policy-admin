# PAS PII handling

This document describes the policy-service boundary for regulated policy-holder data. The
classification and storage choices follow [ADR D7](../adr/0001-pas-target-architecture.md#d7-authentication-and-authorization)
and [Q-26](../modernization/quirk-register.md#q-26).

## Data stored

`pas.policy_holders` keeps the contact and identifying fields used by inquiry in their target
columns. Date of birth, SSN last four, tax ID, and credit score are never stored in cleartext
columns. The schema omits those legacy column names and stores ciphertext in
`date_of_birth_enc`, `ssn_last4_enc`, `tax_id_enc`, and `credit_score_enc`. The policy-holder
repository decrypts only after reading a row and binds each value to its table, column, and
customer identifier.

The legacy customer copybook contains cleartext values at the import/export boundary. Treat those
records as regulated data in memory and in transit; do not log, persist, or attach them to
diagnostics.

## Encryption and envelope format

`PiiCipher` uses AES-256-GCM with a fresh cryptographically random 12-byte IV and a 128-bit
authentication tag. The version-1 binary envelope is:

1. ASCII `P` magic byte and version byte `1`;
2. one-byte key-ID length followed by the ASCII key ID;
3. a 32-bit key version;
4. the 12-byte IV;
5. ciphertext followed by the GCM authentication tag.

The additional authenticated data is UTF-8
`policy_holders.<encrypted-column>:<cust_id>`. Moving a ciphertext to another regulated column
or customer therefore fails authentication. Key ID and version select the active or a retained
previous key; new ciphertext is always written with the active key. Decryption failures are exposed
as a generic crypto error and never include plaintext or ciphertext.

Encryption keys and the separate blind-index key are Base64-encoded 32-byte values. The service
fails startup when a value is missing, malformed, or the wrong length. `PiiCrypto` rejects a
blind-index key equal to any configured encryption key with a constant-time byte comparison.

For local development, `make dev-env` generates distinct random keys into the ignored
`pas-platform/.env`. Those are disposable local keys, not backups and not production secrets. In
deployed environments, supply `PAS_PII_ENCRYPTION_KEY_ID`,
`PAS_PII_ENCRYPTION_KEY_VERSION`, `PAS_PII_ENCRYPTION_KEY`, and `PAS_PII_BLIND_INDEX_KEY` from the
platform secrets store. The batch application is not given PII keys.

### Rotation

Set the new ID, version, and encryption key as active and retain old key material under
`pas.pii.encryption.previous-keys` until every envelope written with it has been re-encrypted or
otherwise retired. Keep the blind-index key stable when exact tax-ID lookup continuity is required;
rotating it requires a controlled index rebuild. Do not remove old key material while ciphertext
still depends on it.

## Tax-ID lookup

`tax_id_bidx` is a 32-byte HMAC-SHA256 value. The normalized input is Unicode NFKC, with spaces and
hyphens removed, and uppercase using `Locale.ROOT`. HMAC uses the independent blind-index key.
The database stores no plaintext tax ID and the index is for exact lookup only; it is not
reversible and is not a general-purpose search mechanism.

## Logging

Both deployables retain Boot 3.5.16's ECS structured console formatter and configure
`PiiMaskingJsonCustomizer` through `logging.structured.json.customizer`. Its string-value processor
masks selected regulated and personal fields and SSN/EIN-shaped identifiers. For pattern-based
Logback layouts, `PiiMaskingConverter` is available as `%maskedMsg`; register it with Logback's
`conversionRule` rather than adding a `logback-spring.xml` to these services.

Scrubbing is a defence-in-depth safeguard, not permission to log PII. Avoid putting sensitive
values in log messages, MDC, exception details, metric tags, or tracing attributes in the first
place.
