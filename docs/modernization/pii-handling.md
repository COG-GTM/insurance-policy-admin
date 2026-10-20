# PII handling for POLICY_HOLDERS (AB-521)

**Security sign-off: Pending: security.** Nothing below has been reviewed or approved by the
security team yet. This is the engineering proposal to review.

## Regulated fields

| DB2 column (ACMEINS.POLICY_HOLDERS) | Copybook field | Target column (`pas.policy_holders`) | Protection |
| --- | --- | --- | --- |
| `DATE_OF_BIRTH DATE` | `CUST-DOB PIC 9(08)` | `date_of_birth_enc VARCHAR(255)` | AES-256-GCM |
| `SSN_LAST4 CHAR(4)` | `CUST-SSN-LAST4 PIC X(04)` | `ssn_last4_enc VARCHAR(255)` + `ssn_last4_bidx CHAR(64)` | AES-256-GCM + HMAC blind index |
| `TAX_ID CHAR(10)` | `CUST-TAX-ID PIC X(10)` | `tax_id_enc VARCHAR(255)` + `tax_id_bidx CHAR(64)` | AES-256-GCM + HMAC blind index |
| `CREDIT_SCORE SMALLINT` | `CUST-CREDIT-SCORE PIC 9(03)` | `credit_score_enc VARCHAR(255)` | AES-256-GCM |

Name, address, phone and email are personal data too. They stay plaintext in this ticket (they are
searched by `IX_PH_NAME` / `IX_PH_COMPANY` today) but are covered by the logging rules below. Whether
they also need field encryption is an open question for security (see `open-questions.md`).

## Encryption at rest

- Application-level AES-256-GCM, 96-bit random IV per value, 128-bit tag
  (`pas-common` `com.acme.insurance.pas.common.pii.PiiCipher`).
- Stored value: Base64 of `version(1) | keyIdLen(1) | keyId | iv(12) | ciphertext+tag`. The key id is
  bound as GCM additional authenticated data, so values cannot be swapped between key ids and key
  rotation can be done by re-encrypting rows whose key id is old.
- Applied by JPA `AttributeConverter`s in `pas-policy-service`
  (`com.acme.insurance.pas.policy.pii.Encrypted{String,LocalDate,Integer}Converter`). The database,
  its backups and replicas only ever see ciphertext. Postgres storage/disk encryption is still
  expected underneath (platform responsibility).
- Plaintext forms: DOB as ISO `yyyy-MM-dd`, SSN last 4 and tax id as stored, credit score as decimal.

## Blind indexes (lookup)

- `HMAC-SHA256(blindIndexKey, purpose || 0x00 || normalize(value))`, lower-case hex
  (`BlindIndexer`). `normalize` removes spaces/dashes and upper-cases.
- Purposes (`BlindIndexPurpose`): `pas.policy_holders.tax_id`, `pas.policy_holders.ssn_last4`. Each
  column uses its own purpose so equal values do not correlate across columns.
- Maintained by `PolicyHolderBlindIndexListener` on insert/update; query with
  `PolicyHolderRepository.findByTaxId(indexer, taxId)` / `findBySsnLast4BlindIndex`.
- SSN last 4 has only 10,000 values, so its blind index is guessable by anyone holding the HMAC
  key; it is only useful combined with other criteria (name, DOB). DOB and credit score have no
  index (no lookup use case in the COBOL programs).

## Keys and configuration

| Property | Environment variable | Notes |
| --- | --- | --- |
| `pas.pii.encryption-key` | `PAS_PII_ENCRYPTION_KEY` | Base64, 256-bit. Required, no default. |
| `pas.pii.blind-index-key` | `PAS_PII_BLIND_INDEX_KEY` | Base64, 256-bit, must differ from the encryption key. Required. |
| `pas.pii.key-id` | `PAS_PII_KEY_ID` | Label stored with each ciphertext. Default `k1`. |

- Spring fails to start when either key is missing, not Base64, not 32 bytes, or both keys are equal
  (`PiiConfiguration`). `docker-compose.yml` uses `${PAS_PII_…:?…}` so the local stack refuses to
  start without them; `.env.example` explains how to generate local keys
  (`openssl rand -base64 32`). No key is committed anywhere; tests generate random keys per run.
- In deployed environments the keys come from the secret store (e.g. a KMS-backed secret mounted as
  env vars). Which KMS, who owns the keys, and the rotation period are for security to decide.

## Logging and masking

- `PolicyHolderEntity.toString()` and `CustomerRecord.toString()` print the customer id only;
  `RecordValues.toString()` prints field names only; `PiiProperties.toString()` never prints keys.
- Crypto exceptions never include plaintext or key material.
- `PiiMasking` gives display masks: SSN last 4 → `***9`, tax id → last 2 characters, DOB → year
  only, credit score → `[REDACTED]`.
- `DataModelIT.piiIsEncryptedAtRestFindableByBlindIndexAndNeverLogged` turns on Hibernate SQL and
  bind-parameter logging (`org.hibernate.orm.jdbc.bind=TRACE`), saves and reads a policy holder, logs
  the entity and record, and asserts no SSN last 4, tax id, DOB or credit score appears in any log
  event. Bind logs only ever contain ciphertext and blind indexes.

## Data migration (later ticket)

DB2 → Postgres migration must encrypt in the loader (through the same converters or `PiiCipher`)
and never stage plaintext PII in intermediate files or tables. Extract layouts that contain these
fields (AB-527) need the consumer-side decision on masking.

## Pending human actions

- Pending: security — review and sign off the algorithm, key management, blind-index design and
  the list of protected fields.
- Pending: security — choose KMS / secret store, key owner and rotation policy.
