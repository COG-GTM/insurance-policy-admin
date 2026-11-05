# PAS target data model (AB-521)

How the legacy ACMEINS DB2 schema and the four PAS copybooks map to the Java 21 platform, how
personal data is protected, and what is assumed about tables PAS does not own. Decisions follow
[ADR 0001](../adr/0001-pas-target-architecture.md) D3 (PostgreSQL 16 + Flyway) and D9 (PII).
The frozen sources are `sql/ddl/create-tables.sql` and `cobol/copybooks/*.cpy`; neither is edited.

## 1. Migrations

| Version | Location | What |
|---|---|---|
| V10 | postgresql | All seven ACMEINS tables, their keys, foreign keys, indexes, defaults and `POLICY_SEQ`, 1:1 with the DB2 DDL |
| V11 | postgresql | `TERRITORY_FACTORS` becomes a reference table (positive-factor CHECK); new `pasapp.class_factors` |
| V11 | db2 | `PASAPP.CLASS_FACTORS` for DB2 while it is the system of record (DBA-applied) |
| V12 | postgresql | PII classification comments on `POLICY_HOLDERS` and the `acmeins.policy_holders_masked` view |
| V13 | postgresql | Q-18 fix: `UNDERWRITING_DECISIONS` keyed by a generated `DECISION_ID`, same-day decisions kept |
| V50 | postgresql, db2 | `PASAPP.EVENT_OUTBOX`: transactional outbox for the PAS events (AB-525) |
| V51 | postgresql, db2 | `PASAPP.POLICY_AUDIT`: append-only write audit (PostgreSQL trigger blocks UPDATE/DELETE) (AB-525) |
| V52 | postgresql, db2 | `PASAPP.DEFAULT_COVERAGES`: POLNEW default coverages per policy type, seeded (AB-525) |

V14-V19 stay free for this ticket's follow-ups (column protection once Security decides, section 5).

**How migrations are applied** (the decision AB-519 left to this ticket):

- Local dev and CI: `pas-policy-service` runs Flyway at start-up (`spring.flyway.*`), and
  `AcmeinsSchemaIT` applies every PostgreSQL migration to an empty PostgreSQL 16 container.
- Shared environments (test, pre-prod, prod): a separate migration job runs Flyway once per release
  with the schema-owner account, before the new application version starts. Application pods run
  with `SPRING_FLYWAY_ENABLED=false` and a DML-only account, so the running service cannot change
  DDL. `pas-batch` never migrates (already `spring.flyway.enabled: false`).
- DB2 (`db/migration/db2`): reviewed and applied by the DBA team through change management, never by
  the application. Only objects in schema `PASAPP` are created; `ACMEINS` stays as in `sql/`.

## 2. Table mapping (DB2 to PostgreSQL)

Names, column order, types, nullability, defaults, keys and constraint names are preserved
(`AcmeinsSchemaIT.columnsMatchTheFrozenDb2Ddl` parses `sql/ddl/create-tables.sql` and compares).

| DB2 | PostgreSQL | Note |
|---|---|---|
| `CHAR(n)`, `VARCHAR(n)` | `char(n)`, `varchar(n)` | Both blank-pad `char`; repositories strip trailing blanks on read |
| `DECIMAL(p,s)` | `numeric(p,s)` | Exact; Java `BigDecimal` with scale `s` |
| `SMALLINT`, `INTEGER` | `smallint`, `integer` | |
| `DATE` | `date` | Java `LocalDate` |
| `TIMESTAMP` | `timestamp(6)` | DB2 default precision 6; Java `LocalDateTime` |
| `CURRENT TIMESTAMP` / `CURRENT DATE` | `localtimestamp` / `current_date` | Session time zone must be the business time zone (set by the platform) |
| `IN ACMEDB.PASTS01` | dropped | Storage is a platform concern |
| `POLICY_SEQ AS INTEGER START WITH 1000000 ... CACHE 50` | same | Max 2147483647, no cycle. Cached values are lost per session, so gaps appear as on DB2 |

Deliberately not changed in the target (dual-run compatibility):

- No `POLICIES.POLICYHOLDER_ID` foreign key to `POLICY_HOLDERS` (Q-37), until profiling (FU-16) proves no orphans.
- No CHECK constraints on code columns (Q-35); unknown codes are stored and kept.
- Audit columns (`UPDATED_BY`, `CALC_BY`, `PROCESSED_BY`, `UNDERWRITER_ID`, `OVERRIDE_BY`) stay `CHAR(8)` (Q-518-1).
- Policy number generation is not implemented: only `POLICY_SEQ` is ported; the format is blocked on FU-05 (Q-08).

Deliberate deviation: V13 changes the `UNDERWRITING_DECISIONS` primary key to `DECISION_ID` (Q-18,
decision "fix"). Migrated rows keep their values. While DB2 is the system of record the CDC sink
merges DB2 rows on `(POLICY_NUMBER, DECISION_DATE)`, which DB2 still guarantees unique (AB-529).

## 3. Copybooks to domain types

Domain types live in `pas-domain` (`com.acme.insurance.pas.domain`), framework-free. Codecs in
`com.acme.insurance.pas.domain.copybook` convert records to and from bytes (EBCDIC code page 037
by default, any charset accepted). `CopybookLayoutsTest` parses `cobol/copybooks/*.cpy` and fails
if the Java layouts drift from the copybooks.

| Copybook | Length | Domain type | Table |
|---|---|---|---|
| POLICY-RECORD | 125 | `Policy` | POLICIES |
| COVERAGE-RECORD | 124 | `Coverage` | COVERAGES |
| PREMIUM-RECORD | 105 | `Premium` | PREMIUMS |
| CUSTOMER-RECORD | 365 | `PolicyHolder` | POLICY_HOLDERS |

Also: `TerritoryFactor`, `ClassFactor` (reference tables) and `RatingFactors.NEUTRAL` (1.0000).

| COBOL | Java | Rule |
|---|---|---|
| `PIC X(n)` | `String` | Trailing spaces removed; all spaces read as `null`; `null` writes spaces; longer values rejected |
| `PIC 9(n)` | `int` / `Integer` | Non-digits rejected; `null` writes zeros |
| `PIC 9(8)` date | `LocalDate` | YYYYMMDD, strict calendar (20230229 rejected); `00000000` reads as `null` |
| `PIC S9(i)V9(s) COMP-3` | `BigDecimal`, scale `s` | Sign C/D written; A/C/E/F and B/D accepted; negative zero reads as 0; bad nibbles rejected |
| 88-levels | enum per field | See below |

Packed decimals are never rounded or truncated by the codec or the domain records: a value with
more decimal places or more digits than the picture is rejected. COBOL would truncate silently
(Q-06); rating code rounds explicitly with `RoundingMode.DOWN` before building a record.

Copybook vs table differences the codecs make explicit:

- `POLICY-LAST-UPDATED` and `CUST-LAST-UPDATED` are `PIC 9(8)` dates but the columns are
  `TIMESTAMP`: the copybook carries only the date (Q-521-1).
- Nullable numeric columns (`RENEWAL_COUNT`, `RISK_SCORE`, `CREDIT_SCORE`, `COINSURANCE_PCT`) have
  no null in `PIC 9(n)`: null is written as zero and reads back as zero (Q-521-2).
- `RISK_SCORE` is `SMALLINT` in DB2 but unsigned `PIC 9(03)`: negatives and values above 999
  cannot be encoded and are rejected.

**88-levels and enums.** Each code field is stored as its raw string, and a typed accessor (for
example `Policy.policyType()`) returns `Optional<PolicyType>`. Values outside the 88-levels are
kept rather than rejected, because production data contains them (Q-35: `CGL`, `DWEL`, risk tiers
`A`/`B`). `UnderwritingStatus` includes `RS` and `RM`, which UNDWRT writes but the copybook does
not list (Q-17); `inCopybookDomain()` tells them apart. Enums: `PolicyType`, `PolicyStatus`,
`UnderwritingStatus`, `CoverageType`, `CoverageStatus`, `CustomerType`, `RiskTier`,
`InstallmentCode`, `EndorsementType`, `YesNo`.

## 4. Rating reference tables

`ACMEINS.TERRITORY_FACTORS` (existing) and `PASAPP.CLASS_FACTORS` / `pasapp.class_factors` (new)
have the same shape: code, `EFFECTIVE_DATE`, `RATING_FACTOR DECIMAL(7,4) > 0`. The factor for a
rating date is the row with the latest `EFFECTIVE_DATE` on or before it
(`RatingFactorJdbcRepository`). No row means the neutral factor 1.0000, which is what PREMBAT uses
today (Q-03), so loading no data keeps parity. Loading the authoritative values is FU-03, and
switching rating to them is an approved behaviour change owned by AB-522.

Class factors are a new table in `PASAPP` rather than `ACMEINS`, so the frozen legacy schema is
never extended (ADR 0001 D3). Register entry: DS-06 in `docs/adr/0001-components.json`.

## 5. Personal data (POLICY_HOLDERS)

| Column | Class | Storage (proposal) | Non-production | Logs, events, metrics |
|---|---|---|---|---|
| `DATE_OF_BIRTH` | RESTRICTED | Storage encryption | Synthetic | Never |
| `SSN_LAST4` | RESTRICTED | Storage encryption + column-level tokenisation | Synthetic | Never |
| `TAX_ID` | RESTRICTED | Storage encryption + column-level tokenisation | Synthetic | Never |
| `CREDIT_SCORE` | RESTRICTED | Storage encryption | Synthetic | Never |
| names (incl. company name: may be a sole trader's own name), address lines, city, zip, phone, email | PERSONAL | Storage encryption | Masked or synthetic | Never |
| `CUST_ID`, type, state, country, risk tier, consent, dates | internal | Storage encryption | As is | Allowed |

What the code does today:

- `@Pii(RESTRICTED|PERSONAL)` marks every personal field of `PolicyHolder`. `PolicyHolder.toString()`
  prints only internal fields, so logging a holder (or an exception that includes it) cannot leak
  PII; `PolicyHolderPiiTest` checks every annotated value against `toString()`.
- Codec and constraint error messages name the field, never its content.
- `PolicyHolderJdbcRepository` logs nothing; SQL errors carry bind markers, not values.
- V12 records the class of every column as a column comment and adds
  `acmeins.policy_holders_masked`: initials only for names and company name, the domain only for
  email, `****` for SSN last four and tax id, and no date of birth (nor any value derived from it,
  such as birth year), address, phone or credit score. The email domain is kept for support
  triage; it can identify a holder with a personal domain, so Security must accept it explicitly
  (section 7) or the view masks it fully.
  Reporting, support and every non-production reader get grants on the view only (DBA, per
  environment).
- Fixtures and tests use synthetic values only (`example.invalid`, zeros).

**Column-level protection: decision pending with Security (FU-21).** During dual-run the PostgreSQL
tables are a CDC replica of DB2, whose `SSN_LAST4 CHAR(4)` and `TAX_ID CHAR(10)` hold clear text.
Options:

1. *Format-preserving tokenisation at CDC ingest and in the service* (recommended): tokens fit
   `CHAR(4)`/`CHAR(10)`, so the column contract and the copybook stay unchanged; detokenisation is a
   separate audited call to the platform token vault (keys in platform KMS).
2. *Envelope encryption in new `bytea` columns* (`ssn_last4_enc`, `tax_id_enc`) with the clear
   columns emptied after cutover: strongest, but adds columns and a contract change for consumers.
3. *pgcrypto in the database*: rejected, keys would have to reach the database session.

Whichever is chosen lands as V14+ in this ticket's range and as a `PiiProtector` port in
`pas-domain`. Until then storage encryption (platform) is the only control at rest, which is why
non-production environments must never receive production rows (ADR 0001 D9).

## 6. ACMEINS.CLAIMS (owned by the Claims team)

PAS does not port, create, read or write `ACMEINS.CLAIMS`. Assumptions recorded for the Claims team
(FU-17):

- The legacy programs read it for open-claim counts and 5-year incurred totals per policy
  (POLRNW, UNDWRT; Q-02, Q-15). New PAS code gets these through an agreed Claims interface only;
  `LegacyBoundaryRulesTest` fails any direct reference in new code.
- The join key is `POLICY_NUMBER CHAR(12)`, the same value as `ACMEINS.POLICIES.POLICY_NUMBER`;
  PAS will not change the format of existing numbers, so Claims needs no re-keying.
- No foreign key exists or will be added between CLAIMS and the PAS tables in either database.
- The CDC replication to PostgreSQL excludes CLAIMS; it is not in `acmeins` (verified by
  `AcmeinsSchemaIT`).

## 7. Sign-offs

| Area | Owner | Status |
|---|---|---|
| Column-level protection option, masking rules, no-PII-in-logs controls (section 5) | Security | pending |
| Masked view keeps the email domain (section 5) | Security | pending |
| PII classification, masked view, non-production data policy | Data governance | pending |
| Type mapping, V10-V13, migration job model, `PASAPP.CLASS_FACTORS` on DB2 | DBA team | pending |
| ACMEINS.CLAIMS assumptions (section 6) | Claims team | pending |
| Q-18 fix and Q-521-1 / Q-521-2 decisions | Product owner + ARB | pending |
| V50-V52 on DB2 (`PASAPP` outbox, audit, default coverages; INSERT/SELECT-only grant on the audit table) | DBA team | pending |
