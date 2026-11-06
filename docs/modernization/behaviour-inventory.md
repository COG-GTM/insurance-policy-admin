# PAS behaviour inventory (AB-517)

**Status:** draft for review. The mainframe team and the product owner have not reviewed it yet (see the sign-off table in [README.md](README.md)).
**Scope:** what PAS does today according to the source in this repository: COBOL programs, JCL jobs, DB2 DDL, copybooks and the Spring Boot 1.5 facade. Things production may do differently are flagged **needs verification** and listed in [open-questions.md](open-questions.md).
**Companion docs:** [quirk-register.md](quirk-register.md) (every quirk/defect, with an owner and a proposed preserve/fix decision) and [legacy-facts.json](legacy-facts.json) (machine-extracted facts).

The tables between `BEGIN/END GENERATED` markers are produced by `tools/discovery/pas_inventory.py` from the legacy source, and `make inventory` fails in CI if they go stale. The rest of the document is written by hand. Each claim cites `file:line` on the integration branch.

Contents:
1. [System context](#1-system-context)
2. [DB2 tables and CRUD matrix](#2-db2-tables-and-crud-matrix)
3. [Interfaces per unit](#3-interfaces-per-unit)
4. [CICS transactions](#4-cics-transactions)
5. [Batch jobs](#5-batch-jobs)
6. [REST facade](#6-rest-facade-java-facade)
7. [Integration points](#7-integration-points)
8. [Record layouts](#8-record-layouts)
9. [Referenced but not in this repo](#9-referenced-but-not-in-this-repository)
10. [Status / code domains](#10-status-and-code-domains)

---

## 1. System context

```
                 3270 / BMS maps (not in repo)
CSR / UW ───────────────────────────────┐
                                        ▼
                ┌──────────── CICS region ───────────────┐
                │ PNEW POLNEW   PRWL POLRNW  PEND POLEND │──TDQ 'ACME' (*)──► MQ ──► Underwriting queue / FileNet
                │ PQRY POLQRY   PUWR UNDWRT              │──TSQ ENDORSEMENT-AUDIT
                └───────────────┬────────────────────────┘
                                │ EXEC SQL (static plans, not in repo)
                                ▼
         ┌──────────── DB2 v12 z/OS, subsystem DBPD, schema ACMEINS ────────────┐
         │ POLICIES COVERAGES PREMIUMS ENDORSEMENTS UNDERWRITING_DECISIONS     │
         │ POLICY_HOLDERS TERRITORY_FACTORS   POLICY_SEQ   [CLAIMS: Claims team]│
         └───┬───────────────┬──────────────────────┬───────────────┬──────────┘
             │ CA-7 batch    │                      │ ODBC linked   │ JDBC (read-only)
             ▼               ▼                      ▼ server        ▼
  PREMIUM-BATCH 01:00   DAILY-EXTRACT 23:00   Broker Portal     java-facade (Spring Boot 1.5)
  (PREMBAT → PREMIUMS)  ── FTP ──► Claims     (SQL Server)      GET /api/v1/policies/...
                        MONTHLY-EXPOSURE 1st ── FTP ──► Actuarial (SAS)
```
(*) Both CICS programs write to TD queue names that are longer than 4 characters, so both resolve to queue `ACME` ([Q-13](quirk-register.md#q-13)).

## 2. DB2 tables and CRUD matrix

C = insert, R = select/cursor, U = update, D = delete. Extracted from `EXEC SQL` blocks and in-stream JCL SQL.

<!-- BEGIN GENERATED: crud-matrix -->
| Table | POLEND (CICS PEND) | POLNEW (CICS PNEW) | POLQRY (CICS PQRY) | POLRNW (CICS PRWL) | PREMBAT (batch pgm) | UNDWRT (CICS PUWR) | DAILY-EXTRACT (JCL job POLEXT) | MONTHLY-EXPOSURE (JCL job MONEXP) | PREMIUM-BATCH (JCL job PREMBAT) |
|---|---|---|---|---|---|---|---|---|---|
| COVERAGES |  | C | R | U |  |  | R | R |  |
| ENDORSEMENTS | CR |  |  |  |  |  |  |  |  |
| POLICIES | RU | C | R | RU | R | RU | R | R |  |
| POLICY_HOLDERS |  | R | R |  |  | R |  |  |  |
| PREMIUMS |  |  |  |  | C |  |  |  |  |
| TERRITORY_FACTORS |  |  |  |  | R |  |  |  |  |
| UNDERWRITING_DECISIONS |  |  |  |  |  | C |  |  |  |
| CLAIMS (not in repo DDL) |  |  |  | R |  | R |  |  |  |
<!-- END GENERATED: crud-matrix -->

Notes:
- `POLICY_SEQ` (sequence, start 1000000, cache 50) is read only by POLNEW (`NEXT VALUE FOR`).
- `CLAIMS` is `ACMEINS.CLAIMS`. The Claims team owns it and its DDL is not in this repo. POLRNW reads `POLICY_NUMBER, CLAIM_STATUS`. UNDWRT reads `POLICY_NUMBER, CLAIM_DATE, INCURRED_AMOUNT`. Out of scope for migration (epic): it is read through an agreed interface ([FU-17](open-questions.md)).
- `TERRITORY_FACTORS` is opened by PREMBAT but never fetched ([Q-03](quirk-register.md#q-03)), so its contents do not affect any output today.
- `PREMIUM-BATCH` has no SQL of its own. It runs program PREMBAT (column "PREMBAT (batch pgm)").
- The README's table list leaves out `TERRITORY_FACTORS`.

## 3. Interfaces per unit

<!-- BEGIN GENERATED: interfaces -->
| Unit | Inputs (maps / files) | Outputs (maps / datasets) | Messages / queues | File transfers |
|---|---|---|---|---|
| POLEND | POLEMAPS/POLEMAP | POLEMAPS/POLECONF | TSQ ENDORSEMENT-AUDIT | - |
| POLNEW | POLNMAPS/POLNMAP | POLNMAPS/POLNCONF | TDQ ACME.PAS.UNDERWRITING.REQUEST | - |
| POLQRY | POLQMAPS/POLQMAP | POLQMAPS/POLQDET | - | - |
| POLRNW | POLRMAPS/POLRMAP | POLRMAPS/POLRCONF | - | - |
| PREMBAT | - | PREMIUM-REPORT -> DD PREMRPT | - | - |
| UNDWRT | UWRTMAPS/UWRTMAP | UWRTMAPS/UWRTRES | TDQ ACME.PAS.UNDERWRITING.DECISION | - |
| DAILY-EXTRACT | runs DSNTEP2 | ACME.PAS.EXTRACT.POLICY.DAILY (LRECL 200), ACME.PAS.EXTRACT.COVERAGE.DAILY (LRECL 150) | - | ftp CLAIMSRV.ACME.LOCAL:/claims/import/policy_extract.dat, ftp CLAIMSRV.ACME.LOCAL:/claims/import/coverage_extract.dat |
| MONTHLY-EXPOSURE | runs DSNTEP2 | ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD (LRECL 300) | - | ftp ACTUARIAL.ACME.LOCAL:/actuarial/data/exposure_monthly.csv |
| PREMIUM-BATCH | runs PREMBAT | ACME.PAS.REPORTS.PREMIUM.D&LYYMMDD (LRECL 132) | - | - |
<!-- END GENERATED: interfaces -->

## 4. CICS transactions

All five are pseudo-conversational BMS programs. Each RECEIVEs a map INTO `POLICY-RECORD`, SENDs a result map or a 79-byte error text, and ends with `EXEC CICS RETURN TRANSID(<own>) COMMAREA(256 bytes)`. The COMMAREA is passed through and never read. None of them issues SYNCPOINT, so each task commits on RETURN and an abend rolls back.

### PNEW: POLNEW (new policy)
`cobol/programs/POLNEW.cbl`
- **Input:** map `POLNMAPS/POLNMAP` → POLICY-RECORD (holder id, type, effective date, limit, deductible, premium, agent, branch).
- **Validation** (`POLNEW.cbl:108-134`): holder id required, type required, effective date not in the past, limit > 0, holder exists in POLICY_HOLDERS. The last failure wins ([Q-11](quirk-register.md#q-11)).
- **Processing:** next `POLICY_SEQ` → policy number `'POL' + seq` (overflow defect, [Q-08](quirk-register.md#q-08)). INSERT POLICIES with status `PN`, UW status `PN`, risk 0, web/API flags `N`, updated by `POLNEW`. Dates are not populated ([Q-09](quirk-register.md#q-09)). INSERT default COVERAGES per policy type ([Q-10](quirk-register.md#q-10)).
- **Outputs:** DB2 POLICIES (C), COVERAGES (C). TD queue `ACME.PAS.UNDERWRITING.REQUEST` gets the raw POLICY-RECORD (125 bytes), and a failed write is ignored. Map `POLNCONF` or error text.
- **Errors:** a failed DB2 insert on POLICIES sends the error and RETURNs, ending the task. Coverage insert errors are not checked.
- **Not done:** no rating ([Q-12](quirk-register.md#q-12)), no activation PN→AC ([Q-40](quirk-register.md#q-40)).

### PRWL: POLRNW (renewal)
`cobol/programs/POLRNW.cbl`
- **Input:** map `POLRMAPS/POLRMAP` → policy number.
- **Reads:** POLICIES (number, type, status, premium, expiry, renewal count, UW status). Eligible statuses are `AC` or `EX`. Reads `ACMEINS.CLAIMS` count of OPEN claims, but the result is unused ([Q-02](quirk-register.md#q-02)).
- **Rating:** new premium = old * 1.05, truncated ([Q-05](quirk-register.md#q-05), [Q-06](quirk-register.md#q-06)). Flat 15% cap, which cannot trigger ([Q-04](quirk-register.md#q-04)). The rate-change percentage divides by the old premium with no zero guard, so a zero-premium policy most likely abends ([Q-41](quirk-register.md#q-41)).
- **New term:** effective = old expiry. Expiry = old expiry + 10000 in YYYYMMDD arithmetic, which breaks on 29 Feb ([Q-01](quirk-register.md#q-01)). Renewal count +1, status `AC`, UW status `PN` with no UW request ([Q-39](quirk-register.md#q-39)).
- **Writes:** UPDATE POLICIES. UPDATE COVERAGES sets the dates of active coverages to the new term, and still runs after a policy update failure ([Q-07](quirk-register.md#q-07)).
- **Outputs:** map `POLRCONF` or error text. No messages and no files.
- The header says "Batch mode supported via PREMBAT for mass renewals". PREMBAT does no renewals.

### PEND: POLEND (endorsement)
`cobol/programs/POLEND.cbl`
- **Input:** map `POLEMAPS/POLEMAP` → POLICY-RECORD. The endorsement type is never populated from the map ([Q-19](quirk-register.md#q-19)). Types: CAD, CRM, LCH, ACH, CAN.
- **Reads:** POLICIES `WITH RS USE AND KEEP UPDATE LOCKS`. Only status `AC` can be endorsed.
- **Processing:** computes a pro-rata factor and never uses it ([Q-20](quirk-register.md#q-20)). Next ENDORSEMENT_SEQ = MAX+1 per policy. INSERT ENDORSEMENTS with effective date today and processed by `POLEND`. UPDATE POLICIES.TOTAL_PREMIUM += adjustment (uninitialised). No type-specific behaviour, and CAN does not cancel ([Q-21](quirk-register.md#q-21)).
- **Outputs:** TS queue `ENDORSEMENT-AUDIT` gets POLICY-RECORD ([Q-23](quirk-register.md#q-23)). Map `POLECONF` or error text. Processing continues after an insert error ([Q-22](quirk-register.md#q-22)).

### PQRY: POLQRY (inquiry, read-only)
`cobol/programs/POLQRY.cbl`
- **Input:** map `POLQMAPS/POLQMAP` → policy number (required).
- **Reads:** POLICIES (17 columns). SQLCODE 100 gives 'POLICY NOT FOUND' and a negative code gives 'DB2 ERROR READING POLICY'. Also POLICY_HOLDERS (unchecked) and up to 20 COVERAGES ordered by SEQUENCE_NUM.
- **Outputs:** map `POLQDET` from POLICY-RECORD only. The customer and coverage data are read and thrown away ([Q-24](quirk-register.md#q-24)). No writes.
- The facade offers the same read capability over REST (§6), with different not-found semantics (HTTP 404 for both endpoints).

### PUWR: UNDWRT (underwriting decision)
`cobol/programs/UNDWRT.cbl`
- **Input:** map `UWRTMAPS/UWRTMAP` → policy number.
- **Reads:** POLICIES ⋈ POLICY_HOLDERS (type, premium, limit, branch, cust type, credit score, risk tier). `ACMEINS.CLAIMS` count and incurred amount over the last 5 years. Sum of COVERAGE_LIMIT of active policies in the branch.
- **Risk score** (`UNDWRT.cbl:119-188`):
  | Rule | Points |
  |---|---|
  | Credit score > 0 | (900 − credit) / 2, truncated. Otherwise 400 |
  | Risk tier `U` (substandard) / `P` (preferred) | +200 / −100 (sign lost, [Q-14](quirk-register.md#q-14)) |
  | Claims in 5 years > 3 / > 5 | +150 / +200 more |
  | Loss ratio (incurred / premium × 100) > 80 | +200 (overflow at 1000%, [Q-15](quirk-register.md#q-15)) |
  | Policy limit > 5M / > 10M | +100 / +200 more |
  | Branch accumulation > $500M | +300 (reason lost, [Q-16](quirk-register.md#q-16)) |
- **Decision bands:** < 300 `AP` auto-accept, < 600 `RS` senior UW, < 800 `RM` manager, otherwise `DC` decline ([Q-17](quirk-register.md#q-17)).
- **Writes:** INSERT UNDERWRITING_DECISIONS (underwriter `SYSTEM`; the PK clashes when there are two decisions on the same day, [Q-18](quirk-register.md#q-18)). UPDATE POLICIES UW_STATUS, RISK_SCORE, updated by `UNDWRT`. POLICY_STATUS is not changed.
- **Outputs:** TD queue `ACME.PAS.UNDERWRITING.DECISION` gets POLICY-RECORD (RESP unchecked). Map `UWRTRES` or error text.

## 5. Batch jobs

Scheduled by CA-7. Every job uses DB2 subsystem `DBPD` through IKJEFT01/DSN. Load library: `ACME.PAS.LOADLIB`.

### PREMIUM-BATCH (job PREMBAT, program PREMBAT)
`jcl/PREMIUM-BATCH.jcl`, `cobol/programs/PREMBAT.cbl`. Daily 01:00 EST, CA-7 PAS0050, TIME=(1,).
- **STEP010:** `RUN PROGRAM(PREMBAT) PLAN(PASBATCH)`.
  - Reads: TERRITORY_FACTORS cursor (opened only, [Q-03](quirk-register.md#q-03)). POLICIES with status `AC`, ordered by POLICY_NUMBER.
  - Per policy: base by type → ×1.00 territory → ×1.00 class → ×1.00 experience → tax 3.5% → +$25 surcharge ([Q-27](quirk-register.md#q-27)).
  - Writes: INSERT PREMIUMS (coverage seq 1, factors 1.0000, discount 0, installment `AN`, calc by `PREMBAT`). Not rerunnable ([Q-26](quirk-register.md#q-26)). Stops at the first insert error ([Q-25](quirk-register.md#q-25)). One unit of work for the whole book ([Q-38](quirk-register.md#q-38)).
  - Report: DD `PREMRPT` → `ACME.PAS.REPORTS.PREMIUM.D&LYYMMDD` (FB, LRECL 132). Contains a header, one line per policy and a totals summary ([Q-29](quirk-register.md#q-29)).
  - Return codes: 4 if any insert failed. 16 is intended on report-open failure but the job actually ends with 0 ([Q-28](quirk-register.md#q-28)).
- **STEP020:** IEFBR14, a no-op.
- **STEP030:** TSO SEND 'PREMIUM BATCH COMPLETED WITH WARNINGS' to user PASADMIN. The COND logic is inverted ([Q-28](quirk-register.md#q-28)).
- **Does not:** update POLICIES.TOTAL_PREMIUM, rate per coverage, or renew.

### DAILY-EXTRACT (job POLEXT)
`jcl/DAILY-EXTRACT.jcl`. Daily 23:00 EST, CA-7 PAS0100, TIME=(,30).
- **STEP010:** DSNTEP2 SELECT from POLICIES (13 columns), status IN ('AC','PN') AND LAST_UPDATED >= CURRENT DATE − 1 DAY, ordered by policy. DD `POLOUT` → `ACME.PAS.EXTRACT.POLICY.DAILY` (FB, LRECL 200).
- **STEP020** (skipped if STEP010 RC > 4): DSNTEP2 SELECT from COVERAGES (10 columns), status 'AC', all rows. DD `COVOUT` → `ACME.PAS.EXTRACT.COVERAGE.DAILY` (FB, LRECL 150).
- **STEP030** (skipped if any step RC > 4): plain FTP to `CLAIMSRV.ACME.LOCAL` as user ACMEFTP. Puts `/claims/import/policy_extract.dat` and `/claims/import/coverage_extract.dat`.
- Quirks: DSNTEP2 does not write the output DDs ([Q-30](quirk-register.md#q-30)). Delta/full mix and missing customer extract ([Q-31](quirk-register.md#q-31)). Plaintext FTP ([Q-32](quirk-register.md#q-32)). Fixed DSN with DISP=NEW ([Q-33](quirk-register.md#q-33)).
- The header says the job also feeds the "Broker Portal (linked server refresh)". No step does this, because the Broker Portal reads DB2 directly (§7).

### MONTHLY-EXPOSURE (job MONEXP)
`jcl/MONTHLY-EXPOSURE.jcl`. 1st of month 02:00 EST, CA-7 PAS0200, TIME=(2,).
- **STEP010:** DSNTEP2 SELECT POLICIES ⋈ COVERAGES (both status 'AC'), grouped by policy type, branch, coverage type, rating territory and class code. Outputs `POLICY_COUNT` (counts coverages, [Q-34](quirk-register.md#q-34)), `TOTAL_EXPOSURE` (sum of limits), `TOTAL_PREMIUM` and `TOTAL_DEDUCTIBLE`. DD `EXPOUT` → `ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD` (FB, LRECL 300).
- **STEP020** (skipped if STEP010 RC > 4): plain FTP to `ACTUARIAL.ACME.LOCAL`, puts `/actuarial/data/exposure_monthly.csv`. As written, DSNTEP2 never writes EXPOUT, so this source does not show what the transferred file contains. It is not a CSV produced by this SQL, and its real content needs a production sample ([Q-30](quirk-register.md#q-30)).
- Consumer: Actuarial SAS programs for loss reserving and pricing.

### Schedule and dependencies
| Time (EST) | Job | CA-7 | Depends on | Notes |
|---|---|---|---|---|
| 23:00 daily | DAILY-EXTRACT | PAS0100 | DBPD active | Delta window relies on LAST_UPDATED |
| 01:00 daily | PREMIUM-BATCH | PAS0050 | DBPD active | Writes only PREMIUMS, so it does not affect the next extract's delta |
| 02:00 on the 1st | MONTHLY-EXPOSURE | PAS0200 | DBPD active | Runs after PREMIUM-BATCH on the 1st, but does not read PREMIUMS |

CA-7 triggers, predecessors and restart rules are not in the repo ([FU-12](open-questions.md)).

## 6. REST facade (java-facade)

`java-facade/` is Spring Boot 1.5.22 on Java 8, added in 2022 and owned by t.nguyen@acme.com. It connects over JDBC to `jdbc:db2://mainframe.acme.local:5023/DBPD`, schema ACMEINS, user PASFACAD.
| Endpoint | Reads | Behaviour |
|---|---|---|
| `GET /api/v1/policies/{n}` | POLICIES (19 columns) | 200 JSON or 404 |
| `GET /api/v1/policies/{n}/coverages` | POLICIES (existence check), COVERAGES ordered by SEQUENCE_NUM | 404 if the policy is missing, otherwise 200 with a list (may be empty) |
| `GET /manage/health` | | Actuator, unauthenticated |
| Swagger UI | | springfox 2.9.2 on the classpath |

- Strings are trimmed, decimals are serialized as JSON numbers, dates as `yyyy-MM-dd` and `lastUpdated` as epoch millis. The contract is pinned in `compat/api/snapshots/policies-v1.json`.
- API_FLAG is returned but not enforced ([Q-24](quirk-register.md#q-24)). There is no authentication ([Q-36](quirk-register.md#q-36)).
- Known consumers are not documented in the repo ([FU-10](open-questions.md)).

## 7. Integration points

| # | Integration | Direction / mechanism | PAS side | Payload | Other end (owner) | Security today | Refs |
|---|---|---|---|---|---|---|---|
| I-1 | **Claims extract** | PAS → Claims, nightly plain FTP | DAILY-EXTRACT STEP030 | `policy_extract.dat` (LRECL 200), `coverage_extract.dat` (LRECL 150), fixed-width with the layout unconfirmed | Claims Engine VB6 batch import on `CLAIMSRV.ACME.LOCAL:/claims/import/`. **Owner: Claims team (name pending)** | Clear-text FTP, user ACMEFTP | Q-30, Q-31, Q-32, Q-33 |
| I-2 | **Actuarial exposure feed** | PAS → Actuarial, monthly plain FTP | MONTHLY-EXPOSURE STEP020 | `exposure_monthly.csv` (LRECL 300, content and layout unconfirmed) | SAS loss-reserving/pricing programs on `ACTUARIAL.ACME.LOCAL:/actuarial/data/`. **Owner: Actuarial (name pending)** | Clear-text FTP | Q-30, Q-32, Q-34 |
| I-3 | **Broker Portal** | Broker Portal → DB2, SQL Server linked server over ODBC (read-only) | none (direct DB2 access) | Unknown tables and columns. Presumably POLICIES/COVERAGES/POLICY_HOLDERS | Broker Portal (SQL Server). **Owner: Broker Portal team (name pending)** | DB2 grants unknown | FU-18 |
| I-4 | **Underwriting request** | PAS → UW queue, CICS TDQ → MQ | POLNEW 7000 | POLICY-RECORD (125 bytes) | Underwriting work queue `ACME.PAS.UNDERWRITING.REQUEST`. **Owner: Underwriting ops + MQ admins (pending)** | Internal | Q-13 |
| I-5 | **Underwriting decision** | PAS → UW queue / FileNet, CICS TDQ → MQ | UNDWRT 7000 | POLICY-RECORD (125 bytes, risk score truncated to 3 digits) | `ACME.PAS.UNDERWRITING.DECISION`. **Owner: Underwriting ops (pending)** | Internal | Q-13, Q-14 |
| I-6 | **FileNet document triggers** | PAS → FileNet over MQ | none (the README says "IBM MQ events trigger document creation") | Which queue FileNet listens on is not in the repo (likely I-4/I-5) | IBM FileNet. **Owner: ECM/Document team (pending)** | Unknown | FU-06 |
| I-7 | **ACMEINS.CLAIMS** | PAS reads a Claims-owned DB2 table | POLRNW 3000, UNDWRT 3000 | CLAIM_STATUS, CLAIM_DATE, INCURRED_AMOUNT by POLICY_NUMBER | **Owner: Claims team.** Out of migration scope; needs an agreed read interface | DB2 grants | FU-17 |
| I-8 | **REST facade consumers** | Consumers → facade, HTTP GET | java-facade | Policy/coverage JSON | Unknown internal systems on the VPN. **Owner: t.nguyen@acme.com** | None | Q-36, FU-10 |
| I-9 | **Endorsement audit** | PAS → CICS TSQ | POLEND 6000 | POLICY-RECORD | Unknown reader. **Owner: Compliance (pending)** | Volatile | Q-23 |
| I-10 | **Operator notification** | PAS → TSO user | PREMIUM-BATCH STEP030 | Fixed text | PASADMIN | n/a | Q-28 |

**AB-528 status.** I-4, I-5 and I-6 are replaced by the Kafka domain events `pas.policy.created/renewed/endorsed` and `pas.underwriting.requested/decided`, with a temporary bridge that keeps the 125-byte POLICY-RECORD on the existing queues until the consumers move; I-3 is replaced by the inquiry API (`GET /api/v1/policies?agentCode|branchCode` plus the AB-524 reads) with no DB2 connection. The queue and linked-server definitions are listed for decommission in [`docs/pas/messaging-decommission.md`](../pas/messaging-decommission.md); design and evidence in [`docs/pas/messaging-and-broker-portal.md`](../pas/messaging-and-broker-portal.md). Consumer moves and sign-offs are pending (FU-528-1..5).

## 8. Record layouts

Copybook record lengths, computed with the same rules as the compat gate (`compat/check_layouts.py`). These bytes go to MQ, the TSQ and the screen buffers, and must stay stable.

| Copybook | Bytes | DB2 table | Used by |
|---|---|---|---|
| POLICY-RECORD | 125 | POLICIES | all 6 programs, plus MQ/TSQ payloads |
| COVERAGE-RECORD | 124 | COVERAGES | POLNEW, POLRNW, POLEND, POLQRY, PREMBAT |
| CUSTOMER-RECORD | 365 | POLICY_HOLDERS | POLNEW, POLQRY, UNDWRT |
| PREMIUM-RECORD | 105 | PREMIUMS | POLRNW, POLQRY, PREMBAT |

Datasets: PREMRPT LRECL 132, POLOUT 200, COVOUT 150 and EXPOUT 300, all RECFM=FB. These LRECLs are also guarded by the compat gate. The field layout inside the extract files is not defined in the repo ([Q-30](quirk-register.md#q-30)).

Copybooks and DB2 types differ: copybook dates are `PIC 9(08)` YYYYMMDD while the DB2 columns are `DATE`, and copybook `POLICY-LAST-UPDATED` is `9(08)` while the DB2 column is `TIMESTAMP`. Host-variable conversion relies on the DB2 precompiler/DCLGEN, which is not in the repo.

## 9. Referenced but not in this repository

| Artefact | Referenced from | Why it matters | Owner to supply |
|---|---|---|---|
| `ACMEINS.CLAIMS` DDL and data contract | POLRNW.cbl, UNDWRT.cbl | Renewal/UW read it; needs an agreed interface | Claims team |
| BMS mapsets POLNMAPS, POLRMAPS, POLEMAPS, POLQMAPS, UWRTMAPS (maps POLNMAP/POLNCONF, POLRMAP/POLRCONF, POLEMAP/POLECONF, POLQMAP/POLQDET, UWRTMAP/UWRTRES) | all CICS programs | Define the real input fields (e.g. endorsement type, Q-19) and what users see | Mainframe team |
| CICS CSD: transaction/program definitions, TDQ (DCT) entry `ACME`, TSQ model, MQ bridge/trigger config | all CICS programs | Real message routing (Q-13) | Mainframe team / CICS SysProg |
| MQ queue manager objects `ACME.PAS.UNDERWRITING.REQUEST/DECISION`, FileNet listener config | POLNEW, UNDWRT, README | Event consumers | MQ admins / ECM team |
| DB2 plans/packages `PASBATCH`, `DSNTEP12`, DBRMs, DCLGEN members, bind JCL | JCL | Isolation levels, qualifiers | DBA team |
| Compile/link JCL and load library `ACME.PAS.LOADLIB` | JCL | Confirms the source matches production (Q-08, Q-09, Q-19, Q-29) | Mainframe team |
| CA-7 job definitions PAS0050/PAS0100/PAS0200, triggers, restart and cleanup jobs | JCL headers | Dependencies, reruns (Q-33) | Mainframe ops |
| FTP credentials / NETRC for ACMEFTP; the "encryption step" mentioned in DAILY-EXTRACT | JCL | Q-32 | Security / Mainframe ops |
| Claims VB6 import program and its file spec | DAILY-EXTRACT | Byte layout of I-1 | Claims team |
| Actuarial SAS programs | MONTHLY-EXPOSURE | Layout and semantics of I-2 | Actuarial |
| Broker Portal linked-server definition and queries | README | Tables read (I-3) | Broker Portal team |
| TERRITORY_FACTORS contents, class-factor source table (no DDL for WS-CLASS-TABLE), experience-mod source | PREMBAT | Q-03 table-driven rating | Actuarial / DBA |
| The program that activates policies (PN → AC) | implied | Q-40 | Mainframe team |
| Production sample outputs: premium report, extract files, exposure file, MQ messages | n/a | Parity baselines | Mainframe team |
| Source control history (RTC for COBOL/JCL, SVN for the facade) | README | Confirms the repo copy is current | Mainframe team / t.nguyen |

## 10. Status and code domains

| Field | Values in COBOL 88-levels | Values written by programs | Values seen elsewhere |
|---|---|---|---|
| POLICY_STATUS | AC, PN, CN, EX, LP | PN (POLNEW), AC (POLRNW) | AC (seed) |
| UW_STATUS | AP, PN, DC, RF | PN (POLNEW, POLRNW), AP/RS/RM/DC (UNDWRT) | AP (seed) |
| POLICY_TYPE | AUT, HOM, COM, LIF, HLT | from input | CGL (seed) |
| COVERAGE_TYPE | PROP, LIAB, AUTP, AUTL, WKCP, UMBR, CYBR, TERR | AUTL, AUTP, PROP, LIAB, WKCP (POLNEW) | DWEL, PERS, COLL, PREM, PROD (seed) |
| COVERAGES.STATUS | AC, PN, CN | AC (POLNEW) | AC |
| RISK_TIER | P, S, U | none | A, B (seed) |
| CUST_TYPE | I, C, G | none | I, C |
| ENDORSEMENT_TYPE | CAD, CRM, LCH, ACH, CAN | from WS field (Q-19) | n/a |
| INSTALLMENT_CODE | AN, SA, QT, MO | AN (PREMBAT) | n/a |

See [Q-35](quirk-register.md#q-35) for the drift between these domains and the data.
