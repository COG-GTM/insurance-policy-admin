# PAS behaviour inventory (AB-517)

This is what the legacy Policy Administration System does today, as written in this repository:
five CICS transactions, one batch program, three JCL jobs and the DB2 schema. It also records where
the code contradicts the README. All file references are `path:line` relative to the repository
root. Defects and quirks are only summarised here. Their full description, owner and preserve/fix
decision are in [quirk-register.md](quirk-register.md) (`Q-NN`). Integrations are in
[integrations.md](integrations.md) (`I-NN`), artifacts referenced but absent in
[missing-artifacts.md](missing-artifacts.md) (`M-NN`), and unresolved points in
[open-questions.md](open-questions.md) (`OQ-NN`).

Scope of evidence: only the source in `cobol/`, `jcl/`, `sql/ddl/` and `java-facade/`. BMS maps,
CICS resource definitions, MQ/TDQ definitions and the CA-7 schedule are not in the repository
(M-02 to M-05), so screen field layouts and queue routing are inferred from the program text and are
marked as inferred.

## 1. Inventory at a glance

| Unit | Kind | Program / step | Trigger | DB2 read | DB2 write | Messages / files out |
|------|------|----------------|---------|----------|-----------|----------------------|
| PNEW | CICS txn | POLNEW | 3270 operator | POLICY_HOLDERS; sequence POLICY_SEQ | POLICIES (INSERT), COVERAGES (INSERT) | TDQ write to `ACME.PAS.UNDERWRITING.REQUEST` (I-04); map POLNCONF |
| PRWL | CICS txn | POLRNW | 3270 operator | POLICIES, ACMEINS.CLAIMS | POLICIES (UPDATE), COVERAGES (UPDATE) | map POLRCONF |
| PEND | CICS txn | POLEND | 3270 operator | POLICIES (RS + update locks), ENDORSEMENTS | ENDORSEMENTS (INSERT), POLICIES (UPDATE) | TS queue `ENDORSEMENT-AUDIT` (I-07); map POLECONF |
| PQRY | CICS txn | POLQRY | 3270 operator | POLICIES, POLICY_HOLDERS, COVERAGES | none | map POLQDET |
| PUWR | CICS txn | UNDWRT | 3270 operator | POLICIES ⋈ POLICY_HOLDERS, ACMEINS.CLAIMS, POLICIES (branch sum) | UNDERWRITING_DECISIONS (INSERT), POLICIES (UPDATE) | TDQ write to `ACME.PAS.UNDERWRITING.DECISION` (I-05); map UWRTRES |
| PREMBAT | Batch job (CA-7 PAS0050, daily 01:00) | PREMIUM-BATCH.jcl → PREMBAT | CA-7 | TERRITORY_FACTORS (opened only), POLICIES | PREMIUMS (INSERT) | PREMRPT report (LRECL 132); TSO SEND to PASADMIN (I-08) |
| DAILY-EXTRACT | Batch job (CA-7 PAS0100, daily 23:00) | POLEXT: DSNTEP2 ×2 + FTP | CA-7 | POLICIES, COVERAGES | none | Policy (LRECL 200) and coverage (LRECL 150) files sent by FTP to Claims (I-01) |
| MONTHLY-EXPOSURE | Batch job (CA-7 PAS0200, 1st 02:00) | MONEXP: DSNTEP2 + FTP | CA-7 | POLICIES ⋈ COVERAGES | none | Exposure file (LRECL 300) sent by FTP to Actuarial (I-02) |
| Facade | Java 8 REST (read-only) | `java-facade` | HTTP | POLICIES, COVERAGES | none | JSON (I-10) |

### Table CRUD matrix

C = insert, R = read, U = update. Nothing in the repository deletes rows.

| Table (DDL) | POLNEW | POLRNW | POLEND | POLQRY | UNDWRT | PREMBAT | DAILY-EXTRACT | MONTHLY-EXPOSURE | Facade |
|-------------|--------|--------|--------|--------|--------|---------|---------------|------------------|--------|
| POLICIES (`sql/ddl/create-tables.sql:28-49`) | C | R U | R U | R | R U | R | R | R | R |
| COVERAGES (`sql/ddl/create-tables.sql:63-80`) | C | U | | R | | | R | R | R |
| PREMIUMS (`sql/ddl/create-tables.sql:85-107`) | | | | | | C | | | |
| ENDORSEMENTS (`sql/ddl/create-tables.sql:112-125`) | | | R C | | | | | | |
| UNDERWRITING_DECISIONS (`sql/ddl/create-tables.sql:130-144`) | | | | | C | | | | |
| POLICY_HOLDERS (`sql/ddl/create-tables.sql:149-173`) | R | | | R | R | | | | |
| TERRITORY_FACTORS (`sql/ddl/create-tables.sql:195-201`) | | | | | | R (cursor opened, never fetched) | | | |
| POLICY_SEQ sequence (`sql/ddl/create-tables.sql:184-190`) | R | | | | | | | | |
| ACMEINS.CLAIMS (not in DDL, M-01) | | R | | | R | | | | |

All tables live in schema `ACMEINS`, tablespace `ACMEDB.PASTS01` (`sql/ddl/create-tables.sql:16-23`),
subsystem `DBPD` (`sql/ddl/create-tables.sql:11-13`).

## 2. Record layouts and file lengths

COMP-3 length = ceil((digits + 1) / 2) bytes. COMP-3 field lengths below are computed from the PIC clauses.

| Copybook | Lines | Record length | Notes |
|----------|-------|---------------|-------|
| POLICY-RECORD | `cobol/copybooks/POLICY-RECORD.cpy:11-49` | **125 bytes** | 3 COMP-3 fields (premium 6, deductible 5, limit 7); dates PIC 9(8) YYYYMMDD; FILLER 20. This is also the TDQ/TS message body (I-04, I-05, I-07) |
| COVERAGE-RECORD | `cobol/copybooks/COVERAGE-RECORD.cpy:10-35` | **124 bytes** | limit 7, deductible 5, premium 6 bytes COMP-3; FILLER 15 |
| CUSTOMER-RECORD | `cobol/copybooks/CUSTOMER-RECORD.cpy:10-42` | **365 bytes** | PII: name, address, phone, email, DOB, SSN last 4, tax ID, credit score; FILLER 15 |
| PREMIUM-RECORD | `cobol/copybooks/PREMIUM-RECORD.cpy:10-32` | **105 bytes** | base rate S9(7)V9999 (6), four factors S9(3)V9999 (4 each), discount S9(3)V99 (3), surcharge/tax/installment S9(7)V99 (5 each), total S9(9)V99 (6); FILLER 10 |
| POLNEW DCLPOLICIES | `cobol/programs/POLNEW.cbl:43-62` | n/a (host structure) | dates as X(10), unlike the copybook (Q-28) |
| CICS COMMAREA | e.g. `cobol/programs/POLNEW.cbl:24`, `cobol/programs/POLNEW.cbl:64-65` | 256 bytes in every program | never read or written by program logic. Data arrives through BMS RECEIVE MAP INTO the copybook record |

| JCL DD | Lines | RECFM / LRECL | Content |
|--------|-------|---------------|---------|
| PREMRPT (`ACME.PAS.REPORTS.PREMIUM.D&LYYMMDD`) | `jcl/PREMIUM-BATCH.jcl:28-31` | FB / 132 | PREMBAT report, matches the FD at `cobol/programs/PREMBAT.cbl:29-33` |
| POLOUT (`ACME.PAS.EXTRACT.POLICY.DAILY`) | `jcl/DAILY-EXTRACT.jcl:36-39` | FB / 200 | policy delta extract (but see Q-41) |
| COVOUT (`ACME.PAS.EXTRACT.COVERAGE.DAILY`) | `jcl/DAILY-EXTRACT.jcl:65-68` | FB / 150 | coverage snapshot extract (Q-41) |
| EXPOUT (`ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD`) | `jcl/MONTHLY-EXPOSURE.jcl:30-33` | FB / 300 | exposure summary (Q-41, Q-42) |

`compat/check_layouts.py` guards these copybooks and LRECLs, so any change needs an entry in
`compat/allowed-changes.json`.

## 3. CICS transactions

The five online programs share one skeleton: `ASKTIME`/`FORMATTIME` into `WS-CURRENT-DATE` →
`RECEIVE MAP … INTO(<copybook record>)` → SQL → `SEND MAP … FROM(POLICY-RECORD)`, or
`SEND TEXT` with a 79-character error. Every program then does `RETURN TRANSID(<own>) COMMAREA(DFHCOMMAREA)
LENGTH(256)`, which makes them pseudo-conversational. None checks `EIBCALEN`, none issues
`SYNCPOINT`/`SYNCPOINT ROLLBACK`, and none performs a security check (Q-32). DB2 changes therefore commit
at task end even when an error was shown (Q-14, Q-16, Q-29).

### 3.1 PNEW / POLNEW: new business

- **Inputs:** BMS map `POLNMAP` in mapset `POLNMAPS`, received into POLICY-RECORD (`cobol/programs/POLNEW.cbl:96-106`). The program uses POLICY-HOLDER-ID, POLICY-TYPE, POLICY-EFFECTIVE-DATE, POLICY-LIMIT, POLICY-AGENT-CODE, POLICY-BRANCH-CODE, POLICY-TOTAL-PREMIUM and POLICY-DEDUCTIBLE. The map's field layout is inferred, because the map source is absent (M-02).
- **Outputs:** confirmation map `POLNCONF` from POLICY-RECORD (`cobol/programs/POLNEW.cbl:255-263`), or a text error (`cobol/programs/POLNEW.cbl:265-273`).
- **DB2:**
  - R `POLICY_HOLDERS.CUST_ID` (`cobol/programs/POLNEW.cbl:124-129`).
  - R `NEXT VALUE FOR POLICY_SEQ` from `SYSIBM.SYSDUMMY1` (`cobol/programs/POLNEW.cbl:138-142`).
  - C `POLICIES`, 19 columns, `LAST_UPDATED = CURRENT TIMESTAMP` (`cobol/programs/POLNEW.cbl:165-182`).
  - C `COVERAGES`, one row per default coverage, status `'AC'` (`cobol/programs/POLNEW.cbl:226-237`).
- **Messages:** `WRITEQ TD QUEUE(WS-MQ-QUEUE)` with the 125-byte POLICY-RECORD, queue literal `ACME.PAS.UNDERWRITING.REQUEST` (`cobol/programs/POLNEW.cbl:31-32`, `cobol/programs/POLNEW.cbl:240-253`). A failure is ignored (Q-29). This is the underwriting referral (I-04).
- **Business rules (paragraph level):**
  - `3000-VALIDATE-INPUT` (`cobol/programs/POLNEW.cbl:108-134`):
    - holder ID, type and limit are required;
    - effective date must not be before today;
    - the holder must exist in POLICY_HOLDERS;
    - only the last failing message is kept (Q-30).
  - `4000-GENERATE-POLICY-NUM` (`cobol/programs/POLNEW.cbl:136-146`): `'POL'` + 10-digit sequence, truncated to 12 characters (Q-08).
  - `5000-INSERT-POLICY` (`cobol/programs/POLNEW.cbl:148-189`): status `PN`, UW status `PN`, renewal count 0, risk 0, web flag `N`, API flag `N`, updated-by `POLNEW`. Premium, deductible and limit are taken from the input as keyed (Q-31). The effective, expiry and inception dates are not populated (Q-09). On SQL error the program sends the error and returns (`cobol/programs/POLNEW.cbl:183-188`).
  - `6000-INSERT-COVERAGES` (`cobol/programs/POLNEW.cbl:191-221`): default coverage set by policy type: AUT → AUTL, AUTP; HOM → PROP, LIAB; COM → PROP, LIAB, WKCP; LIF and HLT → none. The values are hardcoded (TODO at `cobol/programs/POLNEW.cbl:193`), and sequence, limit, deductible and premium are uninitialised (Q-10).
- **Quirks:** Q-08, Q-09, Q-10, Q-24, Q-28, Q-29, Q-30, Q-31, Q-32.

### 3.2 PRWL / POLRNW: renewal

- **Inputs:** map `POLRMAP` / `POLRMAPS` into POLICY-RECORD; only POLICY-NUMBER is used (`cobol/programs/POLRNW.cbl:78-98`).
- **Outputs:** map `POLRCONF` (`cobol/programs/POLRNW.cbl:183-190`) or text error (`cobol/programs/POLRNW.cbl:192-198`).
- **DB2:**
  - R `POLICIES` (number, type, status, premium, expiry, renewal count, UW status) (`cobol/programs/POLRNW.cbl:89-98`).
  - R `COUNT(*) ACMEINS.CLAIMS` with `CLAIM_STATUS = 'OPEN'` (`cobol/programs/POLRNW.cbl:111-117`).
  - U `POLICIES` status, effective, expiry, premium, renewal count, UW status, `LAST_UPDATED`, `UPDATED_BY = 'POLRNW'` (`cobol/programs/POLRNW.cbl:154-165`).
  - U `COVERAGES` effective/expiry for `STATUS = 'AC'` rows (`cobol/programs/POLRNW.cbl:174-180`).
- **Messages:** none. The program sets UW status `PN` but sends no referral (Q-17).
- **Business rules:**
  - `3000-CHECK-RENEWAL-ELIGIBILITY` (`cobol/programs/POLRNW.cbl:106-120`): status must be `AC` or `EX`. The open-claims count is not enforced (Q-02).
  - `4000-CALCULATE-NEW-PREMIUM` (`cobol/programs/POLRNW.cbl:122-131`): new premium = old × 1.05, truncated (Q-06).
  - `5000-APPLY-RATE-CAP` (`cobol/programs/POLRNW.cbl:133-141`): if the change is over 15%, premium = old × 1.15 (Q-04). This never triggers (Q-06).
  - `6000-CREATE-RENEWAL-TERM` (`cobol/programs/POLRNW.cbl:143-170`):
    - renewal count + 1 (wraps at 999, Q-18);
    - new effective = old expiry;
    - new expiry = old expiry + 10000 as a number (leap-year defect, Q-01);
    - status `AC`, UW status `PN`.
  - `7000-UPDATE-COVERAGES` (`cobol/programs/POLRNW.cbl:172-181`): moves the active coverages to the new term. Coverage premiums are not re-rated.
  - `WS-COVID-GRACE` (`cobol/programs/POLRNW.cbl:35-37`) is declared and never referenced, so the "2020-02-01 COVID grace period extension" in the change history (`cobol/programs/POLRNW.cbl:11`) has no effect in this code.
  - The header says "Batch mode supported via PREMBAT for mass renewals" (`cobol/programs/POLRNW.cbl:15`), but PREMBAT has no renewal logic.
- **Quirks:** Q-01, Q-02, Q-04, Q-06, Q-16, Q-17, Q-18, Q-28, Q-32.

### 3.3 PEND / POLEND: endorsement

- **Inputs:** map `POLEMAP` / `POLEMAPS` into POLICY-RECORD (`cobol/programs/POLEND.cbl:75-85`). The endorsement type is never received (Q-12).
- **Outputs:** map `POLECONF` (`cobol/programs/POLEND.cbl:178-185`) or text error (`cobol/programs/POLEND.cbl:187-193`).
- **DB2:**
  - R `POLICIES` `WITH RS USE AND KEEP UPDATE LOCKS` (`cobol/programs/POLEND.cbl:87-97`). This serialises concurrent endorsements of one policy.
  - R `COALESCE(MAX(ENDORSEMENT_SEQ),0)+1` (`cobol/programs/POLEND.cbl:133-138`).
  - C `ENDORSEMENTS` (number, seq, type, effective = today, adjustment, `CURRENT TIMESTAMP`, `'POLEND'`) (`cobol/programs/POLEND.cbl:140-151`).
  - U `POLICIES.TOTAL_PREMIUM`, `LAST_UPDATED`, `UPDATED_BY = 'POLEND'` (`cobol/programs/POLEND.cbl:158-166`).
- **Messages:** `WRITEQ TS QUEUE('ENDORSEMENT-AUDIT')` with the 125-byte POLICY-RECORD (`cobol/programs/POLEND.cbl:169-176`, I-07, Q-15).
- **Business rules:**
  - `3000-VALIDATE-ENDORSEMENT` (`cobol/programs/POLEND.cbl:103-112`): only `AC` policies can be endorsed, and a type is required.
  - `4000-CALCULATE-PRORATA` (`cobol/programs/POLEND.cbl:114-129`): factor = (expiry − today) / (expiry − effective) in days, or 1 if the term is ≤ 0. The factor is never used (Q-11).
  - `5000-APPLY-ENDORSEMENT` (`cobol/programs/POLEND.cbl:131-167`): records the endorsement and adds the adjustment to the premium. Nothing else changes (Q-13), and errors are ignored (Q-14).
- **Quirks:** Q-11, Q-12, Q-13, Q-14, Q-15, Q-28, Q-32.

### 3.4 PQRY / POLQRY: inquiry (read-only)

- **Inputs:** map `POLQMAP` / `POLQMAPS`; POLICY-NUMBER is required (`cobol/programs/POLQRY.cbl:61-76`).
- **Outputs:** map `POLQDET` from POLICY-RECORD only (`cobol/programs/POLQRY.cbl:153-160`), or text error (`cobol/programs/POLQRY.cbl:162-168`).
- **DB2:**
  - R `POLICIES`, 17 columns (`cobol/programs/POLQRY.cbl:78-105`). SQLCODE 100 → "POLICY NOT FOUND"; < 0 → "DB2 ERROR READING POLICY".
  - R `POLICY_HOLDERS` ID, type, names, company, phone, email (`cobol/programs/POLQRY.cbl:107-119`), with no SQLCODE check (Q-26).
  - R `COVERAGES` by cursor `COV_CURSOR` ordered by `SEQUENCE_NUM`, at most 20 rows (`cobol/programs/POLQRY.cbl:121-151`).
- **Messages:** none.
- **Business rules:** read-only. Customer and coverage data are read but not sent to the screen (Q-33).
- **Quirks:** Q-26, Q-32, Q-33.

### 3.5 PUWR / UNDWRT: underwriting decision

- **Inputs:** map `UWRTMAP` / `UWRTMAPS`; POLICY-NUMBER is used (`cobol/programs/UNDWRT.cbl:84-117`).
- **Outputs:** map `UWRTRES` (`cobol/programs/UNDWRT.cbl:245-252`) or text error.
- **DB2:**
  - R `POLICIES P JOIN POLICY_HOLDERS C` (type, premium, limit, branch, customer type, credit score, risk tier) (`cobol/programs/UNDWRT.cbl:96-110`).
  - R `COUNT(*)`, `SUM(INCURRED_AMOUNT)` from `ACMEINS.CLAIMS` for the last 5 years (`cobol/programs/UNDWRT.cbl:137-143`).
  - R `SUM(COVERAGE_LIMIT)` of `AC` policies in the branch (`cobol/programs/UNDWRT.cbl:172-178`).
  - C `UNDERWRITING_DECISIONS` (date = today, code, score, reason, `'SYSTEM'`) (`cobol/programs/UNDWRT.cbl:214-224`).
  - U `POLICIES.UW_STATUS`, `RISK_SCORE`, `UPDATED_BY = 'UNDWRT'` (`cobol/programs/UNDWRT.cbl:226-233`).
- **Messages:** `WRITEQ TD` of POLICY-RECORD to `ACME.PAS.UNDERWRITING.DECISION` (`cobol/programs/UNDWRT.cbl:45-46`, `cobol/programs/UNDWRT.cbl:236-243`). The README says MQ events trigger FileNet document creation (I-05).
- **Business rules (scoring):** `3000-CALCULATE-RISK-SCORE` (`cobol/programs/UNDWRT.cbl:119-168`), then `4000-CHECK-ACCUMULATION` (`cobol/programs/UNDWRT.cbl:170-188`).

  | Rule | Points | Reference |
  |------|--------|-----------|
  | Base: credit > 0 → (900 − credit) / 2 (truncated); else 400 | base | `cobol/programs/UNDWRT.cbl:121-126` |
  | Risk tier `U` (substandard) | +200 | `cobol/programs/UNDWRT.cbl:129-131` |
  | Risk tier `P` (preferred) | −100 (unsigned, Q-19) | `cobol/programs/UNDWRT.cbl:132-134` |
  | Claims in 5 years > 3 | +150 | `cobol/programs/UNDWRT.cbl:144-146` |
  | Claims in 5 years > 5 | +200 more | `cobol/programs/UNDWRT.cbl:147-149` |
  | Loss ratio (incurred / premium × 100) > 80 | +200 (overflow, Q-21) | `cobol/programs/UNDWRT.cbl:152-159` |
  | Policy limit > 5,000,000 | +100 | `cobol/programs/UNDWRT.cbl:162-164` |
  | Policy limit > 10,000,000 | +200 more | `cobol/programs/UNDWRT.cbl:165-167` |
  | Branch accumulated AC limit > 500,000,000 | +300 | `cobol/programs/UNDWRT.cbl:181-187` |

  `5000-RENDER-DECISION` (`cobol/programs/UNDWRT.cbl:190-211`) maps the score to a decision:

  | Score | Decision | Code |
  |-------|----------|------|
  | < 300 | auto-accept | `AP` |
  | < 600 | refer to senior underwriter | `RS` |
  | < 800 | refer to UW manager | `RM` |
  | otherwise | auto-decline | `DC` |

  The decision code goes to `UW_STATUS` (Q-23). The policy status is never changed (Q-24).
- **Quirks:** Q-19, Q-20, Q-21, Q-22, Q-23, Q-24, Q-25, Q-26, Q-27, Q-28, Q-29, Q-32.

## 4. Batch jobs

### 4.1 PREMIUM-BATCH.jcl / PREMBAT: premium recalculation

- **Schedule:** daily 01:00 EST, CA-7 job PAS0050 (`jcl/PREMIUM-BATCH.jcl:7`); the job definition itself is absent (M-05).
- **Steps:**
  - STEP010 runs `PREMBAT` under IKJEFT01, `DSN SYSTEM(DBPD)`, plan `PASBATCH` (`jcl/PREMIUM-BATCH.jcl:22-37`).
  - STEP020 is IEFBR14 (no-op) when RC = 0 (`jcl/PREMIUM-BATCH.jcl:42-43`).
  - STEP030 is TSO `SEND 'PREMIUM BATCH COMPLETED WITH WARNINGS' USER(PASADMIN)` when RC ≤ 4 (`jcl/PREMIUM-BATCH.jcl:45-48`, Q-35).
- **Inputs:** `POLICIES` where `POLICY_STATUS = 'AC'` ordered by number (`cobol/programs/PREMBAT.cbl:117-131`). `TERRITORY_FACTORS` is opened but never fetched (`cobol/programs/PREMBAT.cbl:102-115`, Q-03).
- **Outputs:**
  - C `PREMIUMS`, one row per policy: `COVERAGE_SEQ = 1`, term = the policy's effective/expiry, factors as literal 1.0000, discount 0.00, installment `AN`, `CALC_BY = 'PREMBAT'` (`cobol/programs/PREMBAT.cbl:190-224`).
  - Report PREMRPT, 132-byte lines: header with run date, one line per policy "`<policy> PREMIUM: <amount>`", then totals read/updated/errors (`cobol/programs/PREMBAT.cbl:85-100`, `cobol/programs/PREMBAT.cbl:226-250`).
- **Business rules:** `3200-CALCULATE-PREMIUM` (`cobol/programs/PREMBAT.cbl:149-188`):
  1. Base rate by policy type: AUT 850.00, HOM 1200.00, COM 5000.00, LIF 400.00, HLT 3500.00, other 1000.00.
  2. Territory, class and experience factors: × 1.00 each (Q-03).
  3. Tax = modified premium × 0.0350, truncated (Q-07).
  4. Surcharge: a flat 25.00.
  5. Final = modified premium + tax + surcharge.

  The existing premium, limit and deductible are fetched but not used (Q-07). `POLICIES.TOTAL_PREMIUM` is not updated (Q-36).
- **Return codes:** RC 4 if any INSERT failed (`cobol/programs/PREMBAT.cbl:252-257`). The first failed insert ends the fetch loop (Q-05). A report-open failure ends RC 0 (Q-34). There are no commits during the run (Q-36).
- **Quirks:** Q-03, Q-05, Q-07, Q-34, Q-35, Q-36, Q-40.

### 4.2 DAILY-EXTRACT.jcl (job POLEXT): Claims extract

- **Schedule:** daily 23:00 EST, CA-7 job PAS0100 (`jcl/DAILY-EXTRACT.jcl:7`). Requires DB2 `DBPD` (`jcl/DAILY-EXTRACT.jcl:22`).
- **Steps:**
  - STEP010 runs DSNTEP2 (plan DSNTEP12, lib `ACME.PAS.LOADLIB`). It selects 13 policy columns for status `AC`/`PN` with `LAST_UPDATED >= CURRENT DATE - 1 DAY`, ordered by number (`jcl/DAILY-EXTRACT.jcl:31-56`), into POLOUT (FB/200).
  - STEP020 runs DSNTEP2 with all `AC` coverages, 10 columns, ordered by number and sequence (`jcl/DAILY-EXTRACT.jcl:61-81`), into COVOUT (FB/150). It is skipped if STEP010 RC > 4.
  - STEP030 is the z/OS FTP client (`jcl/DAILY-EXTRACT.jcl:86-96`). It connects to `CLAIMSRV.ACME.LOCAL` as user `ACMEFTP` and PUTs the two datasets to `/claims/import/policy_extract.dat` and `/claims/import/coverage_extract.dat`. It is skipped if any earlier RC > 4.
- **Outputs:** the two files to the Claims Engine (VB6 batch import), I-01. The header also promises a customer extract and a Broker Portal refresh, but neither exists (Q-39).
- **Quirks:** Q-37, Q-38, Q-39, Q-40, Q-41.

### 4.3 MONTHLY-EXPOSURE.jcl (job MONEXP): Actuarial exposure

- **Schedule:** 1st of month 02:00 EST, CA-7 job PAS0200 (`jcl/MONTHLY-EXPOSURE.jcl:7`).
- **Steps:**
  - STEP010 runs DSNTEP2 (`jcl/MONTHLY-EXPOSURE.jcl:26-58`). It groups `AC` policies × `AC` coverages by policy type, branch, coverage type, rating territory and class code, giving `POLICY_COUNT` (= coverage rows, Q-42), `TOTAL_EXPOSURE` (sum of coverage limits), `TOTAL_PREMIUM` (sum of coverage premiums) and `TOTAL_DEDUCTIBLE`, into EXPOUT (FB/300).
  - STEP020 is the FTP client (`jcl/MONTHLY-EXPOSURE.jcl:63-71`). It connects to `ACTUARIAL.ACME.LOCAL` as `ACMEFTP` and PUTs to `/actuarial/data/exposure_monthly.csv`. It is skipped if STEP010 RC > 4.
- **Outputs:** one file consumed by Actuarial SAS loss-reserving and pricing programs (`jcl/MONTHLY-EXPOSURE.jcl:13-15`), I-02.
- **Note:** exposure uses coverage-level `PREMIUM`. POLNEW never sets it (Q-10) and PREMBAT never updates it (Q-36), so exposure premium and PREMIUMS diverge.
- **Quirks:** Q-37, Q-41, Q-42.

## 5. REST facade (java-facade)

This is a read-only Spring Boot 1.5 service on port 8080 (`java-facade/src/main/resources/application.properties:9`). It
uses JDBC to `jdbc:db2://mainframe.acme.local:5023/DBPD`, schema `ACMEINS`
(`java-facade/src/main/resources/application.properties:14-16`), and exposes
`GET /api/v1/policies/{policyNumber}` and `GET /api/v1/policies/{policyNumber}/coverages`
(`java-facade/src/main/java/com/acme/insurance/pas/controller/PolicyController.java:33-48`). These
run the SQL at `java-facade/src/main/java/com/acme/insurance/pas/repository/PolicyRepository.java:32-49`.
There is no authentication and actuator security is off
(`java-facade/src/main/resources/application.properties:32-35`, Q-32). Its JSON contract is
pinned by `compat/api/snapshots/policies-v1.json` (I-10).

## 6. Documented behaviour that the code does not have

| README / header claim | What the code does | Ref |
|-----------------------|--------------------|-----|
| POLEND "Calculates pro-rata premium adjustment" | computes the factor, never applies it | Q-11 |
| POLEND handles coverage, limit, address, named-insured changes and cancellation | records an ENDORSEMENTS row only | Q-13 |
| POLRNW "COVID grace period extension" (2020) | flag declared, never used | §3.2 |
| POLRNW "Batch mode supported via PREMBAT for mass renewals" | PREMBAT has no renewal logic | §3.2 |
| PREMBAT "Added territory rating factors" (2003), "experience modification" (2010) | literals 1.00 | Q-03 |
| UNDWRT terrorism (2004), catastrophe zone (2011), cyber (2018) | not implemented | Q-27 |
| DAILY-EXTRACT "Added encryption step" (2012) | no encryption step | Q-37 |
| DAILY-EXTRACT customer extract, Broker Portal refresh | not implemented | Q-39 |
| PREMIUM-BATCH STEP020 "send email" | IEFBR14 no-op | Q-35 |
| POLQRY "Displays policy header, coverages, premium details" | sends POLICY-RECORD only | Q-33 |
| README: PREMBAT "takes ~4 hours for full book" | the code stops at the first duplicate-key failure, so any run after the first in a term should stop early | Q-05, OQ-15 |
