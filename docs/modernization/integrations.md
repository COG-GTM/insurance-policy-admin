# PAS integration points (AB-517)

Every place where PAS exchanges data with another system or team, with protocol, layout and
the owner of the other end. Owners named in the repo are quoted; where the repo names only a
system, the owning team is given as a role and confirming the named contact is an open
question.

| | |
|---|---|
| Epic / ticket | AB-516 / AB-517 |
| Reviews | Pending: Mainframe team lead. Pending: Product Owner (PAS). Pending: each consumer owner listed below. |

See also: [inventory](inventory.md) · [quirk register](quirk-register.md) ·
[missing artifacts](missing-artifacts.md) · [open questions](open-questions.md).

## Summary

| ID | Integration | Direction | Protocol | PAS side | Other end | Other-end owner | Target ticket |
|----|-------------|-----------|----------|----------|-----------|-----------------|---------------|
| [I-01](#i-01) | Claims daily extract | PAS → Claims | DB2 → `DSNTEP2` datasets → clear-text FTP | `jcl/DAILY-EXTRACT.jcl` | `CLAIMSRV.ACME.LOCAL` `/claims/import/*.dat`, Claims Engine VB6 batch import | Claims team (contact TBC) | AB-527 |
| [I-02](#i-02) | Actuarial monthly exposure | PAS → Actuarial | DB2 → `DSNTEP2` dataset → clear-text FTP | `jcl/MONTHLY-EXPOSURE.jcl` | `ACTUARIAL.ACME.LOCAL` `/actuarial/data/exposure_monthly.csv`, SAS models | Actuarial (contact TBC) | AB-527 |
| [I-03](#i-03) | Broker Portal read access | Broker Portal → DB2 | SQL Server linked server, ODBC, read-only | DB2 `ACMEINS` tables (which ones is unknown) | Broker Portal (SQL Server) | Broker Portal team (contact TBC) | AB-528 |
| [I-04](#i-04) | Underwriting request | PAS → UW queue | `EXEC CICS WRITEQ TD` → (assumed) MQ | `POLNEW.cbl` L240-253 | `ACME.PAS.UNDERWRITING.REQUEST` consumer | Underwriting ops (consumer unknown) | AB-528 |
| [I-05](#i-05) | Underwriting decision | PAS → UW queue | `EXEC CICS WRITEQ TD` → (assumed) MQ | `UNDWRT.cbl` L236-243 | `ACME.PAS.UNDERWRITING.DECISION` consumer | Underwriting ops (consumer unknown) | AB-528 |
| [I-06](#i-06) | FileNet document triggers | PAS → FileNet | IBM MQ (per README) | Not identifiable in code | IBM FileNet document creation | Document management / ECM team (contact TBC) | AB-528 |
| [I-07](#i-07) | Endorsement audit queue | PAS → (unknown) | `EXEC CICS WRITEQ TS` | `POLEND.cbl` L169-176 | TS queue `ENDORSEMENT-AUDIT` reader | Compliance (assumed; consumer unknown) | AB-526 / AB-528 |
| [I-08](#i-08) | Claims table read | PAS → Claims DB2 | Direct SQL on `ACMEINS.CLAIMS` | `POLRNW.cbl` L111-117, `UNDWRT.cbl` L137-143 | `ACMEINS.CLAIMS` | Claims team | AB-525 / AB-526 |
| [I-09](#i-09) | REST facade | Clients → PAS | HTTP/JSON, no auth; JDBC to DB2 | `java-facade/` | "Modern client systems" (unnamed) | Facade: T. Nguyen (t.nguyen@acme.com); consumers TBC | AB-524 |
| [I-10](#i-10) | CICS 3270 users | Users ↔ PAS | BMS maps over 3270 | 5 CICS programs | CSRs, underwriters | PAS business operations | AB-524..AB-526 |
| [I-11](#i-11) | Batch operator notification | PAS → operator | TSO `SEND`, report dataset | `jcl/PREMIUM-BATCH.jcl` L45-48 | TSO user `PASADMIN` | Mainframe ops (mainframe-ops@acme.com) | AB-523 |
| [I-12](#i-12) | Customer master | (unknown) → PAS | DB2 `POLICY_HOLDERS` | Read by POLNEW, POLQRY, UNDWRT | Whatever maintains `POLICY_HOLDERS` | Unknown | AB-521 |
| [I-13](#i-13) | Territory factor reference data | (unknown) → PAS | DB2 `TERRITORY_FACTORS` | Opened by PREMBAT | Whatever maintains the table | Actuarial (assumed) | AB-522 |

PAS-side owners from README L129-133: Mainframe team mainframe-ops@acme.com, DBA team
db2-admin@acme.com, Java facade t.nguyen@acme.com.

## Details

### I-01

**Claims daily extract** — `jcl/DAILY-EXTRACT.jcl`, job `POLEXT`, daily 23:00 EST, CA-7 `PAS0100` (L1-7).

| Aspect | Value |
|--------|-------|
| Datasets | `ACME.PAS.EXTRACT.POLICY.DAILY` RECFM=FB LRECL=200 (L36-39); `ACME.PAS.EXTRACT.COVERAGE.DAILY` RECFM=FB LRECL=150 (L65-68). `ACME.PAS.EXTRACT.CUSTOMER.DAILY` declared (L20), never created |
| Policy selection | `POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE, TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT, UW_STATUS, RISK_SCORE` where status `AC`/`PN` and `LAST_UPDATED >= CURRENT DATE - 1 DAY`, ordered by number (L47-55) |
| Coverage selection | `POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE, DESCRIPTION, COVERAGE_LIMIT, DEDUCTIBLE, PREMIUM, EFFECTIVE_DATE, EXPIRY_DATE, STATUS` where `STATUS='AC'` (full snapshot), ordered by number, sequence (L75-80) |
| Byte layout | **Unknown from the repo.** `DSNTEP2` prints a formatted report to `SYSPRINT`; nothing writes `POLOUT`/`COVOUT` as coded ([Q-31](quirk-register.md#q-31)). A production sample and the Claims import spec are needed ([OQ-08](open-questions.md#oq-08)) |
| Transport | z/OS FTP client step to `CLAIMSRV.ACME.LOCAL`, user `ACMEFTP` (credential not in JCL; [M-06](missing-artifacts.md#m-06)); default transfer mode (ASCII, EBCDIC→ASCII translation); remote `/claims/import/policy_extract.dat`, `/claims/import/coverage_extract.dat` (L86-94). Unencrypted |
| Rerun | Not rerunnable the same day (`DISP=(NEW,CATLG,DELETE)`, no delete step) ([Q-32](quirk-register.md#q-32)) |
| Consumer | Claims Engine "VB6 batch import" (L14) |
| Owner of other end | Claims team — named contact **TBC** |
| Sign-off needed | Pending: Claims team (byte-level layout and SFTP cutover) |

### I-02

**Actuarial monthly exposure** — `jcl/MONTHLY-EXPOSURE.jcl`, job `MONEXP`, 1st of month 02:00 EST, CA-7 `PAS0200` (L1-7).

| Aspect | Value |
|--------|-------|
| Dataset | `ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD` RECFM=FB LRECL=300 (L30-33) |
| Content | Grouped by `POLICY_TYPE, BRANCH_CODE, COVERAGE_TYPE, RATING_TERRITORY, CLASS_CODE`: `POLICY_COUNT` (= coverage row count), `TOTAL_EXPOSURE` = sum of coverage limit, `TOTAL_PREMIUM` = sum of coverage premium, `TOTAL_DEDUCTIBLE`; active policies and coverages only; ordered by type, branch (L40-57) |
| Byte layout | DSNTEP2 report, not CSV, despite the `.csv` name ([Q-33](quirk-register.md#q-33)); production sample needed ([OQ-08](open-questions.md#oq-08)) |
| Transport | Clear-text FTP to `ACTUARIAL.ACME.LOCAL`, user `ACMEFTP`; always `/actuarial/data/exposure_monthly.csv` (overwrites last month) (L63-70) |
| Consumer | SAS loss-reserving and pricing programs (L14-15) |
| Owner of other end | Actuarial — named contact **TBC** |
| Sign-off needed | Pending: Actuarial (layout and transport) |

### I-03

**Broker Portal read access** — README L101: "SQL Server linked server to DB2 (read-only ODBC)";
`DAILY-EXTRACT.jcl` L15 also mentions a "linked server refresh".

| Aspect | Value |
|--------|-------|
| Protocol | Microsoft SQL Server linked server over ODBC to DB2 `DBPD` |
| Tables / queries | **Unknown** — no linked-server definition, views, or query list in the repo ([M-08](missing-artifacts.md#m-08)) |
| Credentials | Unknown DB2 auth id |
| Owner of other end | Broker Portal team — named contact **TBC** |
| Migration note | Re-platforming the Broker Portal is out of scope (epic); AB-528 must give it an authenticated read interface before DB2 retires ([OQ-14](open-questions.md#oq-14)) |

### I-04

**Underwriting request** — `POLNEW.cbl` L31-32, L240-253.

| Aspect | Value |
|--------|-------|
| Mechanism | `EXEC CICS WRITEQ TD QUEUE(WS-MQ-QUEUE)` with `WS-MQ-QUEUE = 'ACME.PAS.UNDERWRITING.REQUEST'` (48 bytes). CICS TD queue names are at most 4 characters, so the real routing (TDQ → MQ bridge / extrapartition queue) is defined outside the repo ([M-03](missing-artifacts.md#m-03), [M-04](missing-artifacts.md#m-04)) |
| Payload | `POLICY-RECORD`, 125 bytes, length `LENGTH OF POLICY-RECORD` ([payload](#message-payload-policy-record)) |
| When | After the policy and coverages are inserted, before confirmation |
| Error handling | Response checked then ignored ("Non-fatal", L249-252) — message can be lost silently |
| Consumer | Unknown — presumably the underwriting workflow that eventually runs `PUWR` ([OQ-12](open-questions.md#oq-12)) |

### I-05

**Underwriting decision** — `UNDWRT.cbl` L45-46, L236-243.

| Aspect | Value |
|--------|-------|
| Mechanism | `EXEC CICS WRITEQ TD QUEUE('ACME.PAS.UNDERWRITING.DECISION')` (same naming caveat as I-04) |
| Payload | `POLICY-RECORD` after the decision: `POLICY-UW-STATUS` ∈ `AP`/`RS`/`RM`/`DC`, `POLICY-RISK-SCORE` = score mod 1000 ([Q-17](quirk-register.md#q-17)) |
| Error handling | `RESP` captured, never tested |
| Consumer | Unknown ([OQ-12](open-questions.md#oq-12)) |

### I-06

**FileNet document triggers** — README L102: "IBM MQ events trigger document creation in FileNet".
No program in the repo writes a FileNet-specific queue or message. The candidates are the
two TD queues (I-04, I-05) and the endorsement audit TSQ (I-07), or an MQ-side subscription.

| Aspect | Value |
|--------|-------|
| Protocol | IBM MQ Series 9.2 (README L51) |
| Queue / layout | **Unknown** ([M-04](missing-artifacts.md#m-04), [OQ-12](open-questions.md#oq-12)) |
| Owner of other end | Document management / ECM team — named contact **TBC** |
| Migration note | Re-platforming FileNet is out of scope; AB-528 keeps a compatible trigger |

### I-07

**Endorsement audit queue** — `POLEND.cbl` L169-176, "Added 2016 for regulatory compliance".

| Aspect | Value |
|--------|-------|
| Mechanism | `EXEC CICS WRITEQ TS QUEUE('ENDORSEMENT-AUDIT')` — main or auxiliary TS, no `ITEM`/`REWRITE`; the 17-character name exceeds both the 8-char `QUEUE` and 16-char `QNAME` limits, so the effective name depends on translator behaviour ([OQ-13](open-questions.md#oq-13)) |
| Payload | `POLICY-RECORD` (125 bytes) — contains no endorsement type, sequence or adjustment |
| Consumer | Unknown; TS queues are not durable across CICS restarts unless recoverable |
| Owner of other end | Compliance (assumed) — **TBC** |

### I-08

**`ACMEINS.CLAIMS` read access** — the only PAS dependency on a table it does not own.

| Program | Query | Where |
|---------|-------|-------|
| POLRNW | `SELECT COUNT(*) ... WHERE POLICY_NUMBER = ? AND CLAIM_STATUS = 'OPEN'` | `POLRNW.cbl` L111-117 |
| UNDWRT | `SELECT COUNT(*), COALESCE(SUM(INCURRED_AMOUNT),0) ... WHERE POLICY_NUMBER = ? AND CLAIM_DATE >= CURRENT DATE - 5 YEARS` | `UNDWRT.cbl` L137-143 |

Columns used: `POLICY_NUMBER`, `CLAIM_STATUS`, `CLAIM_DATE`, `INCURRED_AMOUNT`. No DDL in
the repo ([M-01](missing-artifacts.md#m-01)). Owner: Claims team. Per the epic, the Java
platform reads claims through an agreed interface, not the table.

### I-09

**REST facade** — `java-facade/`.

| Aspect | Value |
|--------|-------|
| API | `GET /api/v1/policies/{policyNumber}`, `GET /api/v1/policies/{policyNumber}/coverages`, `GET /manage/health` (README L89-93; `PolicyController.java` L39-59) |
| Contract | `compat/api/snapshots/policies-v1.json`, enforced by `make compat` |
| Back end | JDBC Type 4 to `mainframe.acme.local:5023/DBPD`, auth id from `DB2_USERNAME` (`application.properties` L14-17) |
| Security | None; actuator unsecured ([Q-34](quirk-register.md#q-34)) |
| Consumers | "Modern client systems" — not named anywhere ([OQ-15](open-questions.md#oq-15)) |
| Owner | T. Nguyen (t.nguyen@acme.com, README L133); source in SVN `svn://svn.acme.local/pas-facade/trunk` (README L126) |

### I-10

**CICS 3270 users** — CSRs and underwriters use `PNEW`, `PRWL`, `PEND`, `PQRY`, `PUWR`
through BMS mapsets `POLNMAPS`, `POLRMAPS`, `POLEMAPS`, `POLQMAPS`, `UWRTMAPS` (maps
listed in [inventory](inventory.md#cics-transactions)). Mapset source is not in the repo
([M-02](missing-artifacts.md#m-02)). Owner: PAS business operations.

### I-11

**Batch operator notification** — `PREMIUM-BATCH.jcl` STEP030 TSO `SEND 'PREMIUM BATCH
COMPLETED WITH WARNINGS' USER(PASADMIN)` (L45-48), plus the PREMRPT report dataset
`ACME.PAS.REPORTS.PREMIUM.D&LYYMMDD` (L28-31). Behaviour quirks: [Q-24](quirk-register.md#q-24),
[Q-26](quirk-register.md#q-26). Owner: Mainframe ops.

### I-12

**Customer master** — no PAS program inserts or updates `POLICY_HOLDERS`, yet POLNEW requires
the holder to exist (`POLNEW.cbl` L124-133). Something outside the repo maintains it (CRM
feed, another CICS application, or DBA loads) ([OQ-18](open-questions.md#oq-18)). The table
holds PII (`DATE_OF_BIRTH`, `SSN_LAST4`, `TAX_ID`, `CREDIT_SCORE`; DDL L164-167).

### I-13

**Territory factor reference data** — `TERRITORY_FACTORS` (DDL L195-201) is never written
by PAS and never effectively read ([Q-03](quirk-register.md#q-03)). Who maintains it is
unknown ([OQ-03](open-questions.md#oq-03)).

## Message payload: POLICY-RECORD

The TD and TS queue messages carry the raw `POLICY-RECORD` (`cobol/copybooks/POLICY-RECORD.cpy`,
125 bytes, EBCDIC, COMP-3 amounts). Offsets below are 1-based.

| Offset | Len | Field | PIC | Notes |
|-------:|----:|-------|-----|-------|
| 1 | 12 | `POLICY-NUMBER` | X(12) | `POL` + 9 digits ([Q-27](quirk-register.md#q-27)) |
| 13 | 3 | `POLICY-TYPE` | X(3) | AUT, HOM, COM, LIF, HLT |
| 16 | 2 | `POLICY-STATUS` | X(2) | AC, PN, CN, EX, LP |
| 18 | 8 | `POLICY-EFFECTIVE-DATE` | 9(8) | YYYYMMDD |
| 26 | 8 | `POLICY-EXPIRY-DATE` | 9(8) | YYYYMMDD |
| 34 | 10 | `POLICY-HOLDER-ID` | X(10) | |
| 44 | 6 | `POLICY-AGENT-CODE` | X(6) | |
| 50 | 4 | `POLICY-BRANCH-CODE` | X(4) | |
| 54 | 6 | `POLICY-TOTAL-PREMIUM` | S9(9)V99 COMP-3 | |
| 60 | 5 | `POLICY-DEDUCTIBLE` | S9(7)V99 COMP-3 | |
| 65 | 7 | `POLICY-LIMIT` | S9(11)V99 COMP-3 | |
| 72 | 8 | `POLICY-INCEPTION-DATE` | 9(8) | |
| 80 | 3 | `POLICY-RENEWAL-COUNT` | 9(3) | |
| 83 | 2 | `POLICY-UW-STATUS` | X(2) | AP, PN, DC, RF; UNDWRT also writes RS, RM |
| 85 | 3 | `POLICY-RISK-SCORE` | 9(3) | mod 1000 |
| 88 | 1 | `POLICY-WEB-IND` | X(1) | |
| 89 | 1 | `POLICY-API-FLAG` | X(1) | |
| 90 | 8 | `POLICY-LAST-UPDATED` | 9(8) | |
| 98 | 8 | `POLICY-UPDATED-BY` | X(8) | |
| 106 | 20 | `FILLER` | X(20) | |

The byte layout is guarded by `compat/check_layouts.py`; any Java publisher that must stay
wire-compatible (AB-528) has to reproduce this record exactly.
