# PAS behaviour inventory (AB-517)

What the legacy Policy Administration System does today, program by program and job by job,
grounded in the source in this repository. Every claim cites `file:line`. Where the README
or a source comment says one thing and the code does another, the code wins and the
difference is recorded in the [quirk register](quirk-register.md).

| | |
|---|---|
| Epic / ticket | AB-516 / AB-517 |
| Sources read | `cobol/programs/*.cbl`, `cobol/copybooks/*.cpy`, `jcl/*.jcl`, `sql/ddl/create-tables.sql`, `java-facade/`, `README.md`, `parity/` |
| Reviews | Pending: Mainframe team lead (mainframe-ops@acme.com). Pending: Product Owner (PAS). |

See also: [integrations](integrations.md) · [quirk register](quirk-register.md) ·
[missing artifacts](missing-artifacts.md) · [open questions](open-questions.md).

## Contents

- [System overview](#system-overview)
- [CICS transactions](#cics-transactions)
  - [POLNEW (PNEW) new business](#polnew-pnew-new-business)
  - [POLRNW (PRWL) renewal](#polrnw-prwl-renewal)
  - [POLEND (PEND) endorsement](#polend-pend-endorsement)
  - [POLQRY (PQRY) inquiry](#polqry-pqry-inquiry)
  - [UNDWRT (PUWR) underwriting decision](#undwrt-puwr-underwriting-decision)
- [Batch jobs](#batch-jobs)
  - [PREMIUM-BATCH / PREMBAT](#premium-batch--prembat)
  - [DAILY-EXTRACT](#daily-extract)
  - [MONTHLY-EXPOSURE](#monthly-exposure)
- [Java REST facade](#java-rest-facade)
- [DB2 CRUD matrix](#db2-crud-matrix)
- [Record layouts](#record-layouts)
- [Documentation drift](#documentation-drift)

## System overview

| Component | Runtime | Source |
|-----------|---------|--------|
| 5 CICS transactions | CICS TS 5.6, COBOL, BMS 3270 maps | `cobol/programs/POLNEW.cbl`, `POLRNW.cbl`, `POLEND.cbl`, `POLQRY.cbl`, `UNDWRT.cbl` |
| 1 batch COBOL program | z/OS batch under TSO `IKJEFT01` / DSN | `cobol/programs/PREMBAT.cbl` |
| 3 JCL jobs (CA-7) | JES2; `DSNTEP2` for SQL extracts; z/OS FTP client | `jcl/PREMIUM-BATCH.jcl`, `jcl/DAILY-EXTRACT.jcl`, `jcl/MONTHLY-EXPOSURE.jcl` |
| DB2 v12 schema `ACMEINS` (subsystem `DBPD`) | 7 tables + 1 sequence | `sql/ddl/create-tables.sql` |
| Read-only REST facade | Java 8 / Spring Boot 1.5.22, JDBC to DB2 | `java-facade/` |

All CICS programs share the same shape: `ASKTIME`/`FORMATTIME` for the current date, a BMS
`RECEIVE MAP ... INTO(POLICY-RECORD)`, DB2 work, a BMS `SEND MAP` (or `SEND TEXT` with a
79-byte error message), and `RETURN TRANSID(<own>) COMMAREA(DFHCOMMAREA) LENGTH(256)`
(pseudo-conversational; the 256-byte COMMAREA is passed back unchanged and never read).
None issues an explicit `SYNCPOINT`/`ROLLBACK`; commit happens at task end.

Java targets (from the AB-519 scaffold): `pas-policy-service` packages `inquiry`,
`lifecycle`, `rating`, `underwriting`; `pas-batch` packages `premium`, `extract`,
`exposure`. Parity harness coverage: PREMBAT, POLRNW, POLEND, UNDWRT (`parity/programs/`).

## CICS transactions

### POLNEW (PNEW) new business

| | |
|---|---|
| Transaction / program | `PNEW` / `POLNEW` (`POLNEW.cbl` L2, L5, L277) |
| Purpose | Create a policy header and default coverages, then request underwriting (L13-16) |
| Input | BMS map `POLNMAP` in mapset `POLNMAPS` into `POLICY-RECORD` (L96-106) |
| Output (screen) | `POLNCONF` from `POLICY-RECORD` (L255-263); errors via `SEND TEXT` 79 bytes (L265-273) |
| Output (message) | `EXEC CICS WRITEQ TD QUEUE('ACME.PAS.UNDERWRITING.REQUEST')` from `POLICY-RECORD` (L31-32, L240-253); response ignored |
| Parity harness | Not covered ([OQ-01](open-questions.md#oq-01)) |
| Java target | `pas-policy-service` `lifecycle` (AB-525) |

Flow (`0000-MAIN-LOGIC` L68-82): initialise → receive map → validate → if no error:
generate number, insert policy, insert coverages, write TDQ, send confirmation; else send
error → return.

- **Validation** (L108-134): holder id not blank; type not blank; effective date not before
  today; limit not zero; holder exists in `POLICY_HOLDERS`. Each failure overwrites the
  message — last one wins ([Q-29](quirk-register.md#q-29)).
- **Policy number** (L136-146): `NEXT VALUE FOR POLICY_SEQ` from `SYSIBM.SYSDUMMY1`, then
  `'POL' + 9(10)` into X(12) — truncates the last digit ([Q-27](quirk-register.md#q-27)).
- **Policy insert** (L148-189): status `PN`, UW status `PN`, renewal count 0, risk 0,
  `WEB_INDICATOR='N'`, `API_FLAG='N'`, `UPDATED_BY='POLNEW'`, `LAST_UPDATED=CURRENT TIMESTAMP`.
  Effective/expiry/inception dates are never moved into the host variables
  ([Q-28](quirk-register.md#q-28)). On SQL error: send error and return (L183-188).
- **Default coverages** (L191-238), status `AC`, dates from the policy host variables:
  AUT → `AUTL` Auto Liability, `AUTP` Auto Physical Damage; HOM → `PROP` Dwelling Coverage,
  `LIAB` Personal Liability; COM → `PROP` Commercial Property, `LIAB` General Liability,
  `WKCP` Workers Compensation; LIF/HLT → none. Limit/deductible/premium never set; INSERT
  result unchecked.

| DB2 table | Operation | Where |
|-----------|-----------|-------|
| `POLICY_HOLDERS` | SELECT `CUST_ID` by `CUST_ID` | L124-129 |
| `POLICY_SEQ` (sequence) via `SYSIBM.SYSDUMMY1` | NEXT VALUE | L138-142 |
| `POLICIES` | INSERT (19 columns) | L165-182 |
| `COVERAGES` | INSERT (10 columns), 0-3 rows | L226-237 |

### POLRNW (PRWL) renewal

| | |
|---|---|
| Transaction / program | `PRWL` / `POLRNW` (`POLRNW.cbl` L2, L5, L202) |
| Purpose | Renew a policy for one more year with a rate increase (L13-15) |
| Input | BMS map `POLRMAP` / mapset `POLRMAPS` into `POLICY-RECORD`; `POLICY-NUMBER` is the key (L78-98) |
| Output (screen) | `POLRCONF` from `POLICY-RECORD` (L183-190); errors via `SEND TEXT` (L192-198) |
| Output (message) | None |
| Parity harness | `parity/programs/polrnw` (16 cases) |
| Java target | `pas-policy-service` `lifecycle` (AB-526) |

Flow (L49-65): receive + read → eligibility → if eligible: new premium → rate cap → new term
→ coverage dates → confirmation; else error.

- **Eligibility** (L106-120): status must be `AC` or `EX`. Counts `OPEN` claims in
  `ACMEINS.CLAIMS` into `WS-SQLCODE` and never checks it ([Q-02](quirk-register.md#q-02)).
  No check on how long ago an `EX` policy expired ([OQ-19](open-questions.md#oq-19)).
- **Premium** (L122-141): new = old x 1.05 (hardcoded, [Q-05](quirk-register.md#q-05));
  rate change % computed and capped at a flat 15% ([Q-04](quirk-register.md#q-04)); both
  truncate ([Q-06](quirk-register.md#q-06)); zero premium divides by zero
  ([Q-08](quirk-register.md#q-08)).
- **New term** (L143-170): renewal count + 1 ([Q-07](quirk-register.md#q-07)); new effective
  = old expiry; new expiry = old expiry + 10000 as a number ([Q-01](quirk-register.md#q-01));
  status `AC`, UW status `PN` (no underwriting request is sent). On UPDATE failure an error
  is sent but the flow continues ([Q-09](quirk-register.md#q-09)).
- `WS-COVID-GRACE` (L34-37, "COVID grace period extension" L11) is declared and never used.
- Header comment "Batch mode supported via PREMBAT for mass renewals" (L15) is not true of
  PREMBAT in this repo ([OQ-20](open-questions.md#oq-20)).

| DB2 table | Operation | Where |
|-----------|-----------|-------|
| `POLICIES` | SELECT number, type, status, premium, expiry, renewal count, UW status | L89-98 |
| `ACMEINS.CLAIMS` (Claims-owned, not in DDL) | SELECT COUNT(*) open claims | L111-117 |
| `POLICIES` | UPDATE status, dates, premium, renewal count, UW status, `UPDATED_BY='POLRNW'` | L154-165 |
| `COVERAGES` | UPDATE effective/expiry where `STATUS='AC'` | L174-180 |

### POLEND (PEND) endorsement

| | |
|---|---|
| Transaction / program | `PEND` / `POLEND` (`POLEND.cbl` L2, L5, L197) |
| Purpose (per header) | Coverage add/remove, limit, address, named-insured changes, mid-term cancel; pro-rata premium (L12-17) |
| Input | BMS map `POLEMAP` / mapset `POLEMAPS` into `POLICY-RECORD` (L75-81) |
| Output (screen) | `POLECONF` (L178-185); errors via `SEND TEXT` (L187-193) |
| Output (queue) | `EXEC CICS WRITEQ TS QUEUE('ENDORSEMENT-AUDIT')` from `POLICY-RECORD` (L169-176, "Added 2016 for regulatory compliance") |
| Parity harness | `parity/programs/polend` (14 cases) |
| Java target | `pas-policy-service` `lifecycle` (AB-526) |

Flow (L48-64): receive + read (with update lock) → validate → pro-rata → apply → audit TSQ →
confirmation.

- **Read** (L87-97) uses `WITH RS USE AND KEEP UPDATE LOCKS`, serialising concurrent
  endorsements on the same policy for the rest of the task.
- **Validate** (L103-112): only `AC` policies; endorsement type must not be blank — but the
  type is never populated ([Q-10](quirk-register.md#q-10)).
- **Pro-rata** (L114-129): days remaining / days in term via `INTEGER-OF-DATE`; PIC 9(3)
  truncation ([Q-12](quirk-register.md#q-12), [Q-13](quirk-register.md#q-13)); the factor is
  not used ([Q-11](quirk-register.md#q-11)).
- **Apply** (L131-167): next sequence = `MAX(ENDORSEMENT_SEQ)+1`; INSERT endorsement with
  effective date = today and adjustment = `WS-PREMIUM-ADJUST` (never set); UPDATE policy
  premium += adjustment. INSERT failure does not stop the flow
  ([Q-14](quirk-register.md#q-14)). No type-specific behaviour ([Q-15](quirk-register.md#q-15)).

| DB2 table | Operation | Where |
|-----------|-----------|-------|
| `POLICIES` | SELECT number, status, dates, premium `WITH RS USE AND KEEP UPDATE LOCKS` | L87-97 |
| `ENDORSEMENTS` | SELECT `COALESCE(MAX(ENDORSEMENT_SEQ),0)+1` | L133-138 |
| `ENDORSEMENTS` | INSERT (7 columns; `DESCRIPTION` never written) | L140-151 |
| `POLICIES` | UPDATE `TOTAL_PREMIUM`, `LAST_UPDATED`, `UPDATED_BY='POLEND'` | L160-166 |

### POLQRY (PQRY) inquiry

| | |
|---|---|
| Transaction / program | `PQRY` / `POLQRY` (`POLQRY.cbl` L2, L5, L172) |
| Purpose | Read-only policy lookup for CSRs and underwriters (L12-14) |
| Input | BMS map `POLQMAP` / mapset `POLQMAPS`; policy number required (L61-76) |
| Output (screen) | `POLQDET` from `POLICY-RECORD` only (L153-160); errors via `SEND TEXT` |
| Output (message) | None |
| Parity harness | Not covered ([OQ-01](open-questions.md#oq-01)) |
| Java target | `pas-policy-service` `inquiry` (AB-524), replacing POLQRY and the facade |

- Reads the policy (L78-105: SQLCODE 100 → "POLICY NOT FOUND", < 0 → "DB2 ERROR READING POLICY"),
  the customer (L107-119, unchecked) and up to 20 coverages ordered by `SEQUENCE_NUM`
  (L121-151). Customer and coverage data are not part of the map's `FROM` area
  ([Q-30](quirk-register.md#q-30)).
- The 2022 "API flag check for facade" (L10) is not implemented.

| DB2 table | Operation | Where |
|-----------|-----------|-------|
| `POLICIES` | SELECT 17 columns by number | L79-98 |
| `POLICY_HOLDERS` | SELECT id, type, names, company, phone, email | L108-118 |
| `COVERAGES` | cursor `COV_CURSOR` SELECT type, description, limit, premium, status | L123-147 |

### UNDWRT (PUWR) underwriting decision

| | |
|---|---|
| Transaction / program | `PUWR` / `UNDWRT` (`UNDWRT.cbl` L2, L5, L264) |
| Purpose | Score risk and decide accept / refer / decline for new and renewal policies (L13-22) |
| Input | BMS map `UWRTMAP` / mapset `UWRTMAPS` into `POLICY-RECORD` (L84-90) |
| Output (screen) | `UWRTRES` (L245-252); errors via `SEND TEXT` |
| Output (message) | `WRITEQ TD QUEUE('ACME.PAS.UNDERWRITING.DECISION')` from `POLICY-RECORD` (L45-46, L236-243); response ignored |
| Parity harness | `parity/programs/undwrt` (20 cases) |
| Java target | `pas-policy-service` `underwriting` (AB-525) |

Scoring (`3000-CALCULATE-RISK-SCORE` L119-168, `4000-CHECK-ACCUMULATION` L170-188):

| Rule | Points | Where |
|------|--------|-------|
| Base: `(900 - CREDIT_SCORE) / 2`, or 400 if no credit score | base | L121-126 |
| Risk tier `U` (substandard) / `P` (preferred) | +200 / -100 | L129-134 |
| Claims in last 5 years > 3 / > 5 (cumulative) | +150 / +200 | L137-149 |
| Loss ratio (5-year incurred / total premium x 100) > 80 | +200 | L152-159 |
| Coverage limit > 5,000,000 / > 10,000,000 (cumulative) | +100 / +200 | L162-167 |
| Branch active `COVERAGE_LIMIT` sum > 500,000,000 | +300 | L172-187 |

Decision (L190-211): < 300 `AP` auto-accept; < 600 `RS` senior UW; < 800 `RM` UW manager;
else `DC` decline. Quirks: [Q-16](quirk-register.md#q-16) to [Q-21](quirk-register.md#q-21).

| DB2 table | Operation | Where |
|-----------|-----------|-------|
| `POLICIES` JOIN `POLICY_HOLDERS` | SELECT type, premium, limit, branch, cust type, credit score, risk tier | L96-110 |
| `ACMEINS.CLAIMS` (Claims-owned) | SELECT COUNT(*), SUM(`INCURRED_AMOUNT`) for `CLAIM_DATE >= CURRENT DATE - 5 YEARS` | L137-143 |
| `POLICIES` | SELECT SUM(`COVERAGE_LIMIT`) by branch, `POLICY_STATUS='AC'` | L172-178 |
| `UNDERWRITING_DECISIONS` | INSERT (unchecked; `UNDERWRITER_ID='SYSTEM'`) | L214-224 |
| `POLICIES` | UPDATE `UW_STATUS`, `RISK_SCORE`, `UPDATED_BY='UNDWRT'` | L226-233 |

## Batch jobs

Schedules are from JCL header comments; the CA-7 definitions themselves are not in the repo
([M-05](missing-artifacts.md#m-05)).

### PREMIUM-BATCH / PREMBAT

| | |
|---|---|
| Job / schedule | `PREMBAT` job, daily 01:00 EST, CA-7 `PAS0050` (`jcl/PREMIUM-BATCH.jcl` L1, L7) |
| Steps | STEP010 `IKJEFT01` → `DSN SYSTEM(DBPD) RUN PROGRAM(PREMBAT) PLAN(PASBATCH)` (L22-36); STEP020 `IEFBR14` no-op (L42); STEP030 TSO `SEND ... USER(PASADMIN)` when RC ≤ 4 (L45-48) |
| Inputs | DB2 `POLICIES` with `POLICY_STATUS='AC'` ordered by number (`PREMBAT.cbl` L118-127); `TERRITORY_FACTORS` cursor opened but not read (L104-114) |
| Outputs | DB2 `PREMIUMS` one row per policy (L202-219); report `PREMRPT` → `ACME.PAS.REPORTS.PREMIUM.D&LYYMMDD`, FB LRECL 132 (`PREMBAT.cbl` L23-33; JCL L28-31) |
| Return code | 0 normally; 4 if any INSERT failed (L252-258); intended 16 on report open failure is lost ([Q-25](quirk-register.md#q-25)) |
| Parity harness | `parity/programs/prembat` (batch mode, 25 cases); `pas-rating` `PremiumCalculator` (AB-522, table-driven) matches |
| Java target | `pas-policy-service` `rating` (AB-522) + `pas-batch` `premium` job (AB-523) |

Calculation (`3200-CALCULATE-PREMIUM` L149-188): base premium by type (AUT 850.00, HOM
1200.00, COM 5000.00, LIF 400.00, HLT 3500.00, other 1000.00) x territory 1.00 x class 1.00 x
experience mod 1.00; tax = mod premium x 0.0350 truncated; surcharge 25.00; final = mod + tax
+ surcharge. Quirks [Q-03](quirk-register.md#q-03), [Q-22](quirk-register.md#q-22) to
[Q-26](quirk-register.md#q-26). Single unit of work: no intermediate COMMIT, so an abend
near the end rolls back the full ~4-hour run (README "Known Limitations" #6).

| DB2 table | Operation | Where |
|-----------|-----------|-------|
| `TERRITORY_FACTORS` | cursor OPEN/CLOSE only (no FETCH) | L104-114 |
| `POLICIES` | cursor SELECT number, type, premium, deductible, limit, dates where `AC` | L118-141 |
| `PREMIUMS` | INSERT (16 columns; factors literal 1.0000) | L202-219 |

### DAILY-EXTRACT

| | |
|---|---|
| Job / schedule | `POLEXT` job, daily 23:00 EST, CA-7 `PAS0100` (`jcl/DAILY-EXTRACT.jcl` L1, L7) |
| Purpose | Policy and coverage extracts for the Claims Engine (VB6 batch import) and "Broker Portal (linked server refresh)" (L13-15) |
| STEP010 | `DSNTEP2` SQL: policies with status `AC`/`PN` and `LAST_UPDATED >= CURRENT DATE - 1 DAY` (L31-55); DD `POLOUT` → `ACME.PAS.EXTRACT.POLICY.DAILY`, FB LRECL 200 (L36-39) |
| STEP020 | `DSNTEP2` SQL: all `AC` coverages (L61-80); DD `COVOUT` → `ACME.PAS.EXTRACT.COVERAGE.DAILY`, FB LRECL 150 (L65-68); runs if STEP010 RC ≤ 4 |
| STEP030 | Clear-text z/OS FTP to `CLAIMSRV.ACME.LOCAL` user `ACMEFTP`: `/claims/import/policy_extract.dat`, `/claims/import/coverage_extract.dat` (L86-94) |
| Declared but absent | `ACME.PAS.EXTRACT.CUSTOMER.DAILY` (L20); the 2012 "encryption step" (L11) |
| Java target | `pas-batch` `extract` (AB-527) |

DB2: SELECT `POLICIES` (13 columns) and `COVERAGES` (10 columns), read-only. Quirks
[Q-31](quirk-register.md#q-31), [Q-32](quirk-register.md#q-32). Integration detail:
[I-01](integrations.md#i-01).

### MONTHLY-EXPOSURE

| | |
|---|---|
| Job / schedule | `MONEXP` job, 1st of month 02:00 EST, CA-7 `PAS0200` (`jcl/MONTHLY-EXPOSURE.jcl` L1, L7) |
| Purpose | Exposure summary for actuarial SAS loss-reserving and pricing models (L13-15) |
| STEP010 | `DSNTEP2` SQL: `POLICIES` JOIN `COVERAGES` (both `AC`) grouped by policy type, branch, coverage type, rating territory, class code; `COUNT(*)`, `SUM(COVERAGE_LIMIT)`, `SUM(PREMIUM)`, `SUM(DEDUCTIBLE)` (L40-57); DD `EXPOUT` → `ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD`, FB LRECL 300 (L30-33) |
| STEP020 | Clear-text FTP to `ACTUARIAL.ACME.LOCAL` user `ACMEFTP` → `/actuarial/data/exposure_monthly.csv` (L63-70) |
| Java target | `pas-batch` `exposure` (AB-527) |

Quirk [Q-33](quirk-register.md#q-33). Integration detail: [I-02](integrations.md#i-02).

## Java REST facade

| | |
|---|---|
| Runtime | Java 8, Spring Boot 1.5.22, Docker on a Linux VM (README L54-58) |
| Endpoints | `GET /api/v1/policies/{policyNumber}`, `GET /api/v1/policies/{policyNumber}/coverages` (`PolicyController.java` L39-59); actuator under `/manage` (`application.properties` L33-35) |
| DB2 access | JDBC Type 4 to `mainframe.acme.local:5023/DBPD`, schema `ACMEINS` (`application.properties` L14-17); SELECT `POLICIES` (19 cols) and `COVERAGES` (13 cols, ordered by `SEQUENCE_NUM`) (`PolicyRepository.java` L32-49) |
| Security | None ("relies on network segmentation", `PolicyController.java` L27-28); [Q-34](quirk-register.md#q-34) |
| Contract snapshot | `compat/api/snapshots/policies-v1.json` (enforced by `make compat`) |
| Java target | `pas-policy-service` `inquiry` (AB-524) |

## DB2 CRUD matrix

C = INSERT, R = SELECT, U = UPDATE, D = DELETE. No PAS program deletes rows.

| Table (`ACMEINS.`) | DDL lines | POLNEW | POLRNW | POLEND | POLQRY | UNDWRT | PREMBAT | DAILY-EXTRACT | MONTHLY-EXPOSURE | Facade |
|--------------------|-----------|:------:|:------:|:------:|:------:|:------:|:-------:|:-------------:|:----------------:|:------:|
| `POLICIES` | 28-58 | C | R U | R U | R | R U | R | R | R | R |
| `COVERAGES` | 63-80 | C | U | | R | | | R | R | R |
| `PREMIUMS` | 85-107 | | | | | | C | | | |
| `ENDORSEMENTS` | 112-125 | | | R C | | | | | | |
| `UNDERWRITING_DECISIONS` | 130-144 | | | | | C | | | | |
| `POLICY_HOLDERS` | 149-179 | R | | | R | R | | | | |
| `TERRITORY_FACTORS` | 195-201 | | | | | | (open only) | | | |
| `POLICY_SEQ` (sequence) | 184-190 | next | | | | | | | | |
| `CLAIMS` (Claims-owned, no DDL) | — | | R | | | R | | | | |

Columns written by nobody in this repo: `COVERAGES.COINSURANCE_PCT`, `RATING_TERRITORY`,
`CLASS_CODE`; `PREMIUMS.INSTALLMENT_AMT`; `ENDORSEMENTS.DESCRIPTION`;
`UNDERWRITING_DECISIONS.OVERRIDE_REASON`, `OVERRIDE_BY`; every `POLICY_HOLDERS` column (the
customer master is maintained outside PAS — [OQ-18](open-questions.md#oq-18));
`TERRITORY_FACTORS` (maintained outside PAS).

## Record layouts

Lengths computed with GnuCOBOL 3.1.2 (`LENGTH OF`) from the copybooks; COMP-3 fields are
packed. `POLICY-RECORD` is also the payload of both underwriting TDQ messages and the
endorsement audit TSQ, so its layout is an integration contract
([integrations](integrations.md#message-payload-policy-record)).

| Copybook | Bytes | DB2 table | Notes |
|----------|------:|-----------|-------|
| `POLICY-RECORD.cpy` | 125 | `POLICIES` | Dates are 9(8) YYYYMMDD vs DB2 `DATE`; `POLICY-LAST-UPDATED` 9(8) vs `TIMESTAMP`; `POLICY-RISK-SCORE` 9(3) vs `SMALLINT` ([Q-17](quirk-register.md#q-17)); UW 88-levels lack `RS`/`RM` ([Q-21](quirk-register.md#q-21)) |
| `COVERAGE-RECORD.cpy` | 124 | `COVERAGES` | 88-levels include `UMBR`, `CYBR`, `TERR` which no program creates |
| `CUSTOMER-RECORD.cpy` | 365 | `POLICY_HOLDERS` | PII: DOB, SSN last 4, tax id (handled by AB-521) |
| `PREMIUM-RECORD.cpy` | 105 | `PREMIUMS` | `PREM-BASE-RATE` S9(7)V9999 vs DB2 `DECIMAL(11,4)` |

## Documentation drift

Items where README or comments disagree with the code (useful when reviewing older docs):

| Claim | Reality | Ref |
|-------|---------|-----|
| README "DB2 Tables" lists six tables | `TERRITORY_FACTORS` (and the `POLICY_SEQ` sequence) also exist | README L78-85; DDL L184-201 |
| DAILY-EXTRACT feeds Claims **and** Broker Portal | Only an FTP to the Claims server; Broker Portal reads DB2 directly | `DAILY-EXTRACT.jcl` L13-15, L86-94; README L101 |
| "Added encryption step (regulatory)" 2012 | No encryption step | `DAILY-EXTRACT.jcl` L11 |
| Customer extract output | Never produced | `DAILY-EXTRACT.jcl` L20 |
| PREMIUM-BATCH STEP020 "send email" | `IEFBR14` does nothing; STEP030 is a TSO SEND | `PREMIUM-BATCH.jcl` L40-48 |
| POLRNW "Batch mode supported via PREMBAT" | PREMBAT has no renewal logic | `POLRNW.cbl` L15 |
| POLQRY "Added API flag check for facade" | No API flag logic | `POLQRY.cbl` L10 |
| POLEND "Calculates pro-rata premium adjustment" | Factor computed, adjustment never set | `POLEND.cbl` L17, L114-166 |
| POLNEW "triggers underwriting referral via MQ message" | A CICS TD queue write; MQ routing lives outside the repo | `POLNEW.cbl` L16, L240-253 |
| PREMBAT "Rating factor table - loaded from DB2" | Never loaded | `PREMBAT.cbl` L50, L102-115 |
