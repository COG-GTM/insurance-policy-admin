# PAS target data model

AB-521 ports the six DB2 business tables, policy sequence, and rating reference structures to
PostgreSQL 16 in schema `pas`. Flyway ownership remains with `pas-policy-service`; batch owns only
`pas_batch` and does not migrate `pas` ([ADR D3/D4](../adr/0001-pas-target-architecture.md#d3-target-database)).

## Migrations

| Version | Migration | Contents |
|---------|-----------|----------|
| V1 | `V1__baseline.sql` | The `pas` schema |
| V2 | `V2__acmeins_core_tables.sql` | `policies`, `coverages`, `premiums`, `endorsements`, `underwriting_decisions`, `policy_holders` |
| V3 | `V3__policy_seq.sql` | `pas.policy_seq` |
| V4 | `V4__rating_reference_tables.sql` | Rating factor sets, territory factors, class factors, and seed data |
| V5 | `V5__policy_holder_pii_columns.sql` | Encrypted PII columns and tax-ID blind index |
| V30 | `V30__outbox_and_idempotency.sql` | Transactional event outbox and caller-scoped idempotency keys |
| V31 | `V31__policy_row_version.sql` | Optimistic policy row version |
| V32 | `V32__underwriting_decision_audit.sql` | Surrogate decision key, actor, override reference and policy/date index |
| V33 | `V33__shadow_new_business_underwriting.sql` | PII-free shadow comparison results |

## Core schema

The V2 structures use lowercase snake_case names. DB2 `DECIMAL(p,s)` maps to PostgreSQL
`NUMERIC(p,s)`, `TIMESTAMP` maps to UTC `TIMESTAMPTZ`, `CURRENT TIMESTAMP` maps to `now()`, and
`CURRENT DATE` maps to `CURRENT_DATE`. Legacy `CHAR`, `VARCHAR`, `DATE`, and `SMALLINT` shapes,
defaults, nullability, keys, and relationships are retained; `CHAR` values are right-trimmed by
repository mappers while variable-width strings are not.

| Table | Key | Purpose |
|-------|-----|---------|
| `policies` | `policy_number` | Policy master and status |
| `coverages` | `(policy_number, sequence_num)` | Coverage and rating inputs |
| `premiums` | `(policy_number, coverage_seq, term_effective_date)` | Calculated premium |
| `endorsements` | `(policy_number, endorsement_seq)` | Endorsement audit |
| `underwriting_decisions` | `decision_id` | Decision audit; V32 retains legacy key columns |
| `policy_holders` | `cust_id` | Customer/contact information and encrypted regulated fields |

The V32 decision key supports multiple same-day decisions while retaining legacy columns.
Legacy columns do not gain new code-domain
checks: unknown codes remain representable as `Coded<E>` values and preserve their raw string
([Q-43](quirk-register.md#q-43)). Do not infer that an unknown code is invalid during migration.

`policy_holders` excludes cleartext `date_of_birth`, `ssn_last4`, `tax_id`, and `credit_score` in
V2. V5 adds the corresponding `_enc` bytea columns, plus a nullable `tax_id_bidx` constrained to
32 bytes and indexed for exact lookup. Encryption, AAD, key handling, and blind-index details are
in [PII handling](../security/pii-handling.md).

The former `POLICY_SEQ` has an integer type, starts at 1,000,000, increments by one, has no cycle,
and uses cache 50. PostgreSQL's integer sequence maximum is 2,147,483,647; a next-value request
past it fails rather than wrapping. Cache allocation and rollbacks can leave gaps. This preserves
the no-cycle/overflow intent (the DB2 failure is SQLSTATE 23522) without promising gapless IDs.

## Rating reference data

`rating_factor_sets` makes the selected set explicit and enforces at most one active set with a
partial unique index. V4 seeds:

- `LEGACY`, active, with parity factors of 1.0000;
- `DB2`, inactive, for later source sync;
- `ILLUSTRATIVE`, inactive, with labelled examples only—not filed rates.

Resolve against the active set only. Prefer exact territory and policy-type/class matches over
wildcards, then use the latest eligible `effective_date`. Factor-set switching is assigned to
AB-522; AB-521 does not activate the DB2 or illustrative data. The missing actuarial source is
tracked as [M-16](missing-artifacts.md#m-16), and legacy factor behaviour is in
[Q-03](quirk-register.md#q-03) and [Q-07](quirk-register.md#q-07).

## Copybooks and dates

The fixed-width layouts remain 125-byte policy, 124-byte coverage, 365-byte customer, and 105-byte
premium records. The codecs support IBM037 EBCDIC and ISO-8859-1, use strict character decoding,
trim only trailing spaces, and reject field overflow unless `TRUNCATE` is explicitly selected.
Packed decimals and signed display overpunches preserve their legacy representation. `YYYYMMDD`
dates are strict; `00000000` and blanks decode to null, and `99999999` maps to the high-date
sentinel. COBOL-specific truncation mappings cite [Q-18](quirk-register.md#q-18),
[Q-20](quirk-register.md#q-20), [Q-26](quirk-register.md#q-26), and
[Q-28](quirk-register.md#q-28).

## Coexistence and write ownership

These repositories establish the target persistence boundary, not the production cutover. Before
cutover, only the DB2 sync job may write business rows in `pas`; policy-service and batch
production roles must lack write grants. Local and integration tests exercise repository writes
against disposable PostgreSQL. The grant change belongs to the cutover plan
([ADR D4/D5](../adr/0001-pas-target-architecture.md#d4-schema-ownership-and-flyway-rules)).

No claims table is added because its DDL and integration contract are missing. See
[claims assumptions](claims-table-assumptions.md), [M-01](missing-artifacts.md#m-01), and
[OQ-18](open-questions.md#oq-18).
