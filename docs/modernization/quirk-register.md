# PAS quirk and defect register (AB-517)

Every behaviour below was read from the legacy source in this repository. File references are
`path:line` relative to the repository root and were checked against the tree this document was
committed with. Where a finding depends on how the mainframe is configured (DB2 precompiler options,
CICS resource definitions), the entry says so and links an open question in
[open-questions.md](open-questions.md).

**Decision values**

- **Preserve**: the new platform reproduces the legacy behaviour, so parity runs match. It may be made
  configurable, but the default stays the same as legacy.
- **Fix**: the new platform behaves correctly. Parity runs list the difference as a known difference
  that cites this Q-id.
- **Fix (flagged)**: the corrected behaviour ships behind a configuration flag. The flag defaults to
  the legacy behaviour until the owner turns it on after a parallel run.

Decisions are proposed by the modernization team and become final when the product owner and the
mainframe team sign off (see the sign-off table in [README.md](README.md)). Code and parity known
differences must cite the Q-id.

## Summary

| Q-id | Area | Quirk | Owner | Decision | Ticket |
|------|------|-------|-------|----------|--------|
| Q-01 | POLRNW | Renewal expiry = YYYYMMDD + 10000, ignores leap years | PAS product owner | Fix | AB-526 |
| Q-02 | POLRNW | Open-claims count queried but never enforced | PAS product owner | Fix | AB-526 |
| Q-03 | PREMBAT | Territory cursor opened but never loaded, so all factors are 1.00 | Actuarial | Fix (flagged) | AB-522 |
| Q-04 | POLRNW | Flat 15% renewal rate cap, not state-specific | Compliance / PAS product owner | Preserve (configurable) | AB-526 |
| Q-05 | PREMBAT | PREMIUMS primary key collides on rerun, and the first failed insert ends the run | Mainframe team (batch) | Fix | AB-523 |
| Q-06 | POLRNW | Hardcoded 5% uplift means the 15% cap can never apply | Actuarial | Preserve (configurable) | AB-526 |
| Q-07 | PREMBAT | Hardcoded base rates, 3.5% tax, $25 surcharge, class/experience/schedule mods of 1.0000 | Actuarial | Preserve (configurable) | AB-522 |
| Q-08 | POLNEW | `'POL'` + 10-digit sequence truncated to 12 chars, so 10 sequence values share one policy number | PAS product owner | Fix | AB-525 |
| Q-09 | POLNEW | Effective/expiry/inception date host variables never populated before INSERT | PAS product owner | Fix | AB-525 |
| Q-10 | POLNEW | Coverage sequence, limit, deductible, premium never initialised; LIF/HLT get no coverages | PAS product owner | Fix | AB-525 |
| Q-11 | POLEND | Pro-rata factor computed but premium adjustment never set | PAS product owner | Preserve | AB-526 |
| Q-12 | POLEND | Endorsement type never taken from the input | PAS product owner | Fix | AB-526 |
| Q-13 | POLEND | Endorsement is recorded but never applied (coverage, limit, address, cancel) | PAS product owner | Fix | AB-526 |
| Q-14 | POLEND | Failed ENDORSEMENTS insert still updates the premium, audit and confirmation | PAS product owner | Fix | AB-526 |
| Q-15 | POLEND | Audit trail written to a volatile CICS TS queue with an over-long name | Compliance | Fix | AB-526 |
| Q-16 | POLRNW | Failed POLICIES update still updates coverages and sends confirmation | PAS product owner | Fix | AB-526 |
| Q-17 | POLRNW | No renewal window or idempotency check; UW status ignored; UW reset to PN without a referral | PAS product owner | Fix | AB-526 |
| Q-18 | POLRNW | Renewal count PIC 9(3) wraps 999 → 000 | PAS product owner | Fix | AB-526 |
| Q-19 | UNDWRT | Unsigned risk score loses the sign when the preferred-tier credit goes below zero | Underwriting | Preserve | AB-525 |
| Q-20 | UNDWRT | Risk score ≥ 1000 truncated to 3 digits in the screen/queue record | Underwriting | Fix | AB-525 |
| Q-21 | UNDWRT | Loss ratio ≥ 1000% wraps (S9(3)V99) and can drop the +200 surcharge | Underwriting | Fix | AB-525 |
| Q-22 | UNDWRT | Accumulation-exceeded reason overwritten by the decision text | Underwriting | Fix | AB-525 |
| Q-23 | UNDWRT | Referral codes RS/RM are not in the copybook's UW 88-levels (which use RF) | Underwriting | Preserve | AB-521, AB-525 |
| Q-24 | All | No program moves a policy from PN to AC (no activation step) | PAS product owner | Preserve (open question) | AB-525 |
| Q-25 | UNDWRT | Second decision on the same day collides on the PK, unchecked; underwriter always `SYSTEM` | Underwriting | Fix | AB-525 |
| Q-26 | UNDWRT, POLQRY | Nullable columns selected without indicator variables (null credit score fails underwriting) | Mainframe team (DB2) | Fix | AB-524, AB-525 |
| Q-27 | UNDWRT | Hardcoded score bands, $500M branch accumulation limit; catastrophe/terrorism/cyber scoring never implemented | Underwriting | Preserve (configurable) | AB-525 |
| Q-28 | All | Dates held as PIC 9(8) YYYYMMDD against DB2 DATE columns; ASKTIME into PIC 9(8) | Mainframe team (DB2) | Fix | AB-521 |
| Q-29 | POLNEW, UNDWRT | Queue write failures ignored; 48-char TD queue names | Mainframe team (CICS/MQ) | Fix | AB-528 |
| Q-30 | POLNEW | Validation keeps only the last error; holder lookup runs even when the ID is blank | PAS product owner | Fix | AB-525 |
| Q-31 | POLNEW | New-business premium keyed by the operator, not rated; API flag always `N` | Actuarial / PAS product owner | Preserve | AB-525 |
| Q-32 | All online, facade | No object-level authorization anywhere | Security | Fix | AB-524, AB-525, AB-526 |
| Q-33 | POLQRY | Customer and coverages read but never displayed; 20-coverage cap | PAS product owner | Fix | AB-524 |
| Q-34 | PREMBAT | Report-file open failure sets RC 16 in working storage only, so the step ends RC 0 | Mainframe team (batch) | Fix | AB-523 |
| Q-35 | PREMIUM-BATCH.jcl | Warning alert sent on RC 0 and 4, not on failure; "email" step is IEFBR14 | Mainframe team (batch) | Fix | AB-523 |
| Q-36 | PREMBAT | One unit of work for the whole book; one PREMIUMS row per policy; POLICIES.TOTAL_PREMIUM never updated | Actuarial / mainframe team | Preserve outputs, Fix commit/restart | AB-523 |
| Q-37 | DAILY-EXTRACT, MONTHLY-EXPOSURE | Plaintext FTP, no encryption step despite the 2012 comment, fixed remote names, no checksum | Security | Fix | AB-527 |
| Q-38 | DAILY-EXTRACT | Policy extract is a 1-day delta, coverage extract is a full snapshot; CN/EX/LP excluded | Claims system owner | Preserve | AB-527 |
| Q-39 | DAILY-EXTRACT | Customer extract and Broker Portal refresh documented but not implemented | Claims / Broker Portal owners | Preserve (open question) | AB-527 |
| Q-40 | DAILY-EXTRACT, PREMIUM-BATCH | Fixed DSNs allocated `DISP=(NEW,…)` fail on the next run unless deleted elsewhere | Mainframe team (batch) | Fix | AB-523, AB-527 |
| Q-41 | All extracts | DSNTEP2 writes to SYSPRINT, not to the POLOUT/COVOUT/EXPOUT DDs; delivered layout undefined | Mainframe team (batch) | Fix | AB-527 |
| Q-42 | MONTHLY-EXPOSURE | `POLICY_COUNT` counts coverage rows; ".csv" not CSV; point-in-time, not month-end | Actuarial | Preserve | AB-527 |
| Q-43 | java-facade seed | Seed data uses values outside COBOL domains (`POL-00000001`, type `CGL`, tiers `A`/`B`) | Data migration lead | Preserve | AB-521, AB-529 |
| Q-44 | UNDWRT, POLRNW | CICS RECEIVE failure leaves the performed flow running into later processing | PAS product owner / Underwriting | Fix | AB-525, AB-526 |
| Q-45 | UNDWRT | Negative credit loses its sign when moved into unsigned `CUST-CREDIT-SCORE` | Underwriting | Fix | AB-525 |
| Q-46 | Extract ordering | DB2 EBCDIC collation can order mixed letter/digit codes differently from ASCII; ties are not fully ordered | Mainframe team (batch) | Fix | AB-527 |
| Q-47 | PREMBAT | PREMRPT moves a COMP-3 premium into a display line, yielding non-text bytes in the report | Mainframe team (batch) | Fix | AB-523 |

## Details

### Q-01

**POLRNW renewal expiry = YYYYMMDD + 10000**

- **Location:** `cobol/programs/POLRNW.cbl:148-150` (the comment at `cobol/programs/POLRNW.cbl:146-147` admits the problem).
- **Observed:** the new expiry is the old expiry as a PIC 9(8) number plus 10000. A 29 Feb expiry becomes 29 Feb of the next year (for example 20240229 → 20250229), which is not a valid date. The effective date of the new term is the old expiry (`cobol/programs/POLRNW.cbl:145`).
- **Impact:** an invalid date goes to `POLICIES.EXPIRY_DATE` and `COVERAGES.EXPIRY_DATE` (`cobol/programs/POLRNW.cbl:154-165`, `cobol/programs/POLRNW.cbl:174-180`). DB2 rejects it (the update fails and Q-16 applies), or the term is a day off, depending on how the host variable is converted (Q-28).
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The new expiry is the old expiry plus one calendar year (`LocalDate.plusYears(1)`: 29 Feb → 28 Feb).
- **Rationale:** the comment admits the defect, and the output can be an invalid date. No consumer depends on it.
- **Implemented by:** AB-526.

### Q-02

**POLRNW queries open claims but never enforces the limit**

- **Location:** `cobol/programs/POLRNW.cbl:111-119`.
- **Observed:** `SELECT COUNT(*) … FROM ACMEINS.CLAIMS … CLAIM_STATUS = 'OPEN'` stores the count in `WS-SQLCODE`, a misnamed variable (`cobol/programs/POLRNW.cbl:25`). Nothing reads it afterwards, and a failed query (SQLCODE < 0) is not detected either. The TODO says the business wanted to block renewal when there are more than 3 open claims.
- **Impact:** policies with many open claims renew automatically.
- **Owner:** PAS product owner (with Claims for the data feed).
- **Decision:** **Fix.** Block renewal when open claims > `pas.renewal.max-open-claims` (default 3, from the TODO) and return a 422-style business rejection. If the claims source is unavailable, fail closed.
- **Rationale:** this is the rule the business stated. The CLAIMS table belongs to the Claims team (see `integrations.md` I-06 and `missing-artifacts.md` M-01).
- **Implemented by:** AB-526.

### Q-03

**PREMBAT opens the territory cursor and never fetches it, so every factor is 1.00**

- **Location:** `cobol/programs/PREMBAT.cbl:102-115` (DECLARE/OPEN/CLOSE with no FETCH); the table is declared at `cobol/programs/PREMBAT.cbl:51-54` and never used; the class table at `cobol/programs/PREMBAT.cbl:56-59` is never loaded either. The factors applied are literals: `cobol/programs/PREMBAT.cbl:166-176`. The INSERT writes the literals 1.0000 to TERRITORY_FACTOR, CLASS_FACTOR, EXPERIENCE_MOD and SCHEDULE_MOD (`cobol/programs/PREMBAT.cbl:214-215`).
- **Observed:** territory, class and experience factors are all 1.00 for every policy. `ACMEINS.TERRITORY_FACTORS` (`sql/ddl/create-tables.sql:195-201`) is never actually read. The cursor predicate also compares a PIC 9(8) host variable with a DATE column (Q-28).
- **Impact:** premiums ignore territory. Actuarial models fed by MONTHLY-EXPOSURE assume rated premiums.
- **Owner:** Actuarial.
- **Decision:** **Fix (flagged).** The default (`legacy`) reproduces factor 1.00 for parity. With the flag on, factors come from `TERRITORY_FACTORS` (latest `EFFECTIVE_DATE <=` the run date) and the class table. Actuarial turns the flag on after a parallel run.
- **Rationale:** turning real factors on changes every premium, so it is a rate change that needs filing. Parity must first prove the migration itself is neutral.
- **Implemented by:** AB-522 (rating component and flag); AB-523 uses it in the batch job. Implemented in PR #65.

### Q-04

**Flat 15% renewal rate cap**

- **Location:** `cobol/programs/POLRNW.cbl:29-30` (`WS-RATE-INCREASE-CAP VALUE 15.00`), `cobol/programs/POLRNW.cbl:133-140`.
- **Observed:** if the computed increase is more than 15%, the new premium becomes old × 1.15. No state-specific caps are applied (TODO at `cobol/programs/POLRNW.cbl:135`). Also listed in the README's known limitations.
- **Impact:** possible regulatory exposure in states with lower caps. In practice the cap never applies today (Q-06).
- **Owner:** Compliance, with the PAS product owner.
- **Decision:** **Preserve (configurable).** Default `pas.renewal.rate-cap-pct = 15.00`. Optional per-state overrides (`pas.renewal.state-rate-cap-pct.<ST>`) are empty by default.
- **Rationale:** changing the cap is a regulatory filing decision, not a migration decision.
- **Implemented by:** AB-526.

### Q-05

**PREMIUMS primary key collides on rerun, and the first failed insert stops the run**

- **Location:** PK `(POLICY_NUMBER, COVERAGE_SEQ, TERM_EFFECTIVE_DATE)` at `sql/ddl/create-tables.sql:103-104`; INSERT at `cobol/programs/PREMBAT.cbl:202-219` with `COVERAGE_SEQ = 1` (`cobol/programs/PREMBAT.cbl:192`) and `TERM_EFFECTIVE_DATE = POLICIES.EFFECTIVE_DATE` (`cobol/programs/PREMBAT.cbl:193`); fetch loop `PERFORM 3100-FETCH-POLICY UNTIL SQLCODE NOT = 0` at `cobol/programs/PREMBAT.cbl:129-130`.
- **Observed:** the key does not include the calculation date, so any second run within the same policy term inserts a duplicate key (SQLCODE -803). That covers a same-day rerun, and also the next scheduled daily run. The loop condition then reads the SQLCODE of the failed INSERT, not of the FETCH, so the job stops processing after the first duplicate. It counts 1 error (`cobol/programs/PREMBAT.cbl:220-224`) and ends with RC 4 (`cobol/programs/PREMBAT.cbl:254-257`).
- **Impact:** a rerun after a partial failure processes nothing past the first policy that was already rated. Premiums for policies after that point (in policy-number order) are not recalculated. RC 4 is handled as a warning (Q-35).
- **Owner:** Mainframe team (batch operations).
- **Decision:** **Fix.** Reruns are idempotent: one row per policy, coverage sequence and term (upsert, or delete-and-insert per run inside the chunk transaction). A single policy failure is counted and skipped, and the run continues.
- **Rationale:** stopping the run on the first error is an accident of the loop condition, not intended behaviour. Batch operations need safe reruns.
- **Implemented by:** AB-523.

### Q-06

**Hardcoded 5% renewal uplift, so the 15% cap can never apply**

- **Location:** `cobol/programs/POLRNW.cbl:122-131`.
- **Observed:** new premium = old × 1.05 (`COMPUTE` without `ROUNDED`, so truncated to 2 dp). The rate change % is therefore always 5.00 (or close to it after truncation), which is below the 15.00 cap. If old premium = 0, the percentage calculation divides by zero with no `ON SIZE ERROR` (`cobol/programs/POLRNW.cbl:128-130`); the result depends on the compiler and the job may abend.
- **Impact:** every renewal gets +5% whatever the risk or claims. The cap code never runs.
- **Owner:** Actuarial.
- **Decision:** **Preserve (configurable).** `pas.renewal.uplift-pct` defaults to 5.00, with truncation (RoundingMode.DOWN). A zero old premium is rejected as a business error instead of dividing by zero.
- **Rationale:** parity of the renewal premium. Actuarial owns any change to the uplift.
- **Implemented by:** AB-526.

### Q-07

**PREMBAT hardcoded rates**

- **Location:** base rates by type at `cobol/programs/PREMBAT.cbl:150-164` (AUT 850.00, HOM 1200.00, COM 5000.00, LIF 400.00, HLT 3500.00, other 1000.00); tax rate 0.0350 at `cobol/programs/PREMBAT.cbl:70-72`; flat $25 surcharge at `cobol/programs/PREMBAT.cbl:182-183`; discount 0.00 and installment `AN` literals at `cobol/programs/PREMBAT.cbl:216-217`.
- **Observed:** the premium depends only on the policy type. Limit, deductible, coverages and the existing premium are fetched (`cobol/programs/PREMBAT.cbl:135-141`) but never used. Tax is truncated, not rounded (`cobol/programs/PREMBAT.cbl:179-180`). The results are exact for the current rates: AUT 904.75, HOM 1267.00, COM 5200.00, LIF 439.00, HLT 3647.50, other 1060.00.
- **Impact:** every policy of a type pays the same premium. Tax is not state-specific.
- **Owner:** Actuarial.
- **Decision:** **Preserve (configurable).** The rate tables become configuration (or reference data) with these values as the defaults. No literals in code.
- **Rationale:** parity. Changing a rate is a filing decision.
- **Implemented by:** AB-522. Implemented in PR #65.

### Q-08

**Policy-number generation truncates the last digit**

- **Location:** `cobol/programs/POLNEW.cbl:29` (`WS-POLICY-SEQ PIC 9(10)`), `cobol/programs/POLNEW.cbl:136-146`; target field `POLICY-NUMBER PIC X(12)` at `cobol/copybooks/POLICY-RECORD.cpy:12`; sequence `START WITH 1000000` at `sql/ddl/create-tables.sql:184-190`.
- **Observed:** `STRING 'POL' WS-POLICY-SEQ DELIMITED BY SIZE INTO POLICY-NUMBER` builds 13 characters into a 12-character field, and there is no `ON OVERFLOW`, so the last digit is dropped. Sequence values 1000000 to 1000009 all give `POL000100000`.
- **Impact:** 9 of every 10 new-business attempts fail with a duplicate key on `POLICIES` (`cobol/programs/POLNEW.cbl:183-188`), each wasting a sequence value. The operator sees "DB2 ERROR INSERTING POLICY RECORD" and retries.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** Generate 12-character numbers with no collisions (for example `POL` + 9 digits from the sequence). Existing numbers are migrated unchanged.
- **Rationale:** every new policy number is new, so there is nothing to keep parity with, and the defect blocks most new business.
- **Implemented by:** AB-525.

### Q-09

**POLNEW inserts dates it never set**

- **Location:** `cobol/programs/POLNEW.cbl:148-163` move every DCLPOLICIES field except `POL-EFF-DT`, `POL-EXP-DT` and `POL-INCEPT-DT` (declared at `cobol/programs/POLNEW.cbl:47-48`, `cobol/programs/POLNEW.cbl:55`). They are inserted at `cobol/programs/POLNEW.cbl:165-182` and reused for coverages at `cobol/programs/POLNEW.cbl:235-236`.
- **Observed:** the three host variables hold whatever is in working storage (no `VALUE` clause), but `EFFECTIVE_DATE` and `EXPIRY_DATE` are `NOT NULL DATE` (`sql/ddl/create-tables.sql:32-33`). The entered `POLICY-EFFECTIVE-DATE` (PIC 9(8)) is validated (`cobol/programs/POLNEW.cbl:115-118`) but never moved, and no expiry is derived.
- **Impact:** the INSERT fails with an invalid datetime (SQLCODE -180/-181), unless storage happens to hold a valid date. Either way the stored term is not the one entered.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** Store the entered effective date, set expiry = effective + 1 year, and set inception = effective.
- **Rationale:** the policy term is a core contract attribute.
- **Implemented by:** AB-525.

### Q-10

**POLNEW coverages written from uninitialised fields**

- **Location:** `cobol/programs/POLNEW.cbl:191-238`.
- **Observed:** `ADD 1 TO COV-SEQUENCE-NUM` (`cobol/programs/POLNEW.cbl:224`) starts from an unset value. `COV-LIMIT`, `COV-DEDUCTIBLE` and `COV-PREMIUM` (COMP-3) are never set, so the result is a data exception (S0C7) or garbage. The coverage INSERT has no SQLCODE check (`cobol/programs/POLNEW.cbl:226-237`). The default coverage sets are hardcoded per type: AUT → AUTL+AUTP, HOM → PROP+LIAB, COM → PROP+LIAB+WKCP. LIF and HLT get none (`cobol/programs/POLNEW.cbl:194-220`).
- **Impact:** coverages can be missing or wrong with no error shown.
- **Owner:** PAS product owner.
- **Decision:** **Fix** the initialisation: sequence starts at 1, and limit/deductible come from the policy (premium 0.00 until rated). **Preserve** the default coverage sets per type, as configuration.
- **Rationale:** uninitialised storage is not a business rule. The coverage sets are.
- **Implemented by:** AB-525.

### Q-11

**POLEND computes a pro-rata factor and never uses it**

- **Location:** `cobol/programs/POLEND.cbl:114-129` computes `WS-PRORATA-FACTOR`; `WS-PREMIUM-ADJUST` (`cobol/programs/POLEND.cbl:32`) is never assigned, but is inserted (`cobol/programs/POLEND.cbl:140-151`) and added to the policy premium (`cobol/programs/POLEND.cbl:158-166`). Day counters are unsigned PIC 9(3) (`cobol/programs/POLEND.cbl:29-30`), so the sign of a negative day count is lost and terms over 999 days are truncated.
- **Observed:** the adjustment is whatever working storage holds: normally zero or low-values, and possibly a data exception.
- **Impact:** endorsements never change the premium in practice. The header comment promises a pro-rata adjustment (`cobol/programs/POLEND.cbl:17`).
- **Owner:** PAS product owner.
- **Decision:** **Preserve.** The premium adjustment is 0.00 and the pro-rata factor is not applied. The next PREMBAT run re-rates the policy.
- **Rationale:** a pro-rata charge needs rating rules per endorsement type, and the legacy system never had them. Adding one is a product change for later.
- **Implemented by:** AB-526.

### Q-12

**POLEND never reads the endorsement type**

- **Location:** `WS-ENDORSEMENT-TYPE` at `cobol/programs/POLEND.cbl:33-38`; input goes only into `POLICY-RECORD` (`cobol/programs/POLEND.cbl:76-81`); validation at `cobol/programs/POLEND.cbl:108-111`; INSERT at `cobol/programs/POLEND.cbl:147-148`.
- **Observed:** nothing moves a value into `WS-ENDORSEMENT-TYPE`. If storage is spaces, every endorsement is rejected as "ENDORSEMENT TYPE IS REQUIRED". If it is low-values, the check passes and a junk type is stored. The 88-levels CAD/CRM/LCH/ACH/CAN are never referenced.
- **Impact:** `ENDORSEMENTS.ENDORSEMENT_TYPE` cannot be trusted.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The type is required input, validated against CAD/CRM/LCH/ACH/CAN.
- **Rationale:** the field exists to hold the type.
- **Implemented by:** AB-526.

### Q-13

**POLEND records an endorsement but never applies it**

- **Location:** `cobol/programs/POLEND.cbl:131-167`; header comment at `cobol/programs/POLEND.cbl:12-17`.
- **Observed:** the program writes an ENDORSEMENTS row and rewrites `TOTAL_PREMIUM`, and that is all. Coverages are never added or removed, the limit and the address are never changed, and a cancellation (`CAN`) leaves `POLICY_STATUS = 'AC'`. `ENDORSEMENTS.DESCRIPTION` is never written. The endorsement effective date is always today (`cobol/programs/POLEND.cbl:148`).
- **Impact:** changes to the policy are recorded but have no effect on it. Cancellations do not cancel.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The endorsement API applies the change (coverage add/remove, limit change, address change, cancel → `CN`) in the same transaction, and stores the description and the requested effective date.
- **Rationale:** this is the documented purpose of PEND. Mainframe team to confirm that no other process applies endorsements today (OQ-05).
- **Implemented by:** AB-526.

### Q-14

**POLEND ignores DB2 errors**

- **Location:** `cobol/programs/POLEND.cbl:152-155` sets the error message, but the main line (`cobol/programs/POLEND.cbl:55-59`) has already decided to run 5000→7000. The UPDATE at `cobol/programs/POLEND.cbl:160-166` has no SQLCODE check. On a RECEIVE failure the SELECT still runs (`cobol/programs/POLEND.cbl:82-97`).
- **Observed:** a failed insert still updates the premium, writes the audit record and shows the confirmation screen.
- **Impact:** the operator sees success when nothing was recorded.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The write is all or nothing and any failure is returned to the caller.
- **Implemented by:** AB-526.

### Q-15

**POLEND audit trail kept in a CICS temporary-storage queue**

- **Location:** `cobol/programs/POLEND.cbl:169-176`.
- **Observed:** `WRITEQ TS QUEUE('ENDORSEMENT-AUDIT')` writes the 125-byte POLICY-RECORD (the policy, not the endorsement details). TS queues are not durable unless defined recoverable/auxiliary. The literal is 17 characters, longer than the 8-character `QUEUE` name limit (16 for `QNAME`), so the translator either rejects it or truncates it. What the load module actually uses is unknown (OQ-06). `RESP` is not checked. The comment says this was added "for regulatory compliance".
- **Impact:** the regulatory audit trail can be lost and lacks the endorsement details.
- **Owner:** Compliance.
- **Decision:** **Fix.** A durable audit table, written in the same transaction (with an event on `pas.policy.events.v1`).
- **Implemented by:** AB-526 (audit), AB-528 (event).

### Q-16

**POLRNW continues after a failed POLICIES update**

- **Location:** `cobol/programs/POLRNW.cbl:166-169` performs 9000-SEND-ERROR but does not stop; the main line goes on to `cobol/programs/POLRNW.cbl:59-60`.
- **Observed:** the coverages are still moved to the new term (`cobol/programs/POLRNW.cbl:172-181`, no SQLCODE check), and the confirmation map overwrites the error.
- **Impact:** coverages can end up a term ahead of the policy, and the operator sees success.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The renewal runs in one transaction and any failure rolls it back.
- **Implemented by:** AB-526.

### Q-17

**POLRNW has no renewal window, no idempotency, and no UW referral**

- **Location:** eligibility checks only for status AC/EX (`cobol/programs/POLRNW.cbl:106-110`); UW status is read (`cobol/programs/POLRNW.cbl:92`) but not checked; `UW_STATUS` is set to `PN` (`cobol/programs/POLRNW.cbl:152`) and no queue message is written.
- **Observed:** the same policy can be renewed any number of times, any time before or after expiry, and each run adds another year. A declined (`DC`) policy can be renewed. After renewal the UW status is `PN`, but nothing triggers UNDWRT.
- **Impact:** a double submission extends the term twice. Renewed policies sit in UW pending.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** Renewal is allowed only within a configurable window around expiry, idempotent per term (repeat request → same result), rejected for UW `DC`, and it emits an underwriting request event.
- **Implemented by:** AB-526 (rules), AB-528 (event).

### Q-18

**Renewal count wraps at 999**

- **Location:** `cobol/programs/POLRNW.cbl:144`; `POLICY-RENEWAL-COUNT PIC 9(03)` at `cobol/copybooks/POLICY-RECORD.cpy:34`; the column is SMALLINT (`sql/ddl/create-tables.sql:41`).
- **Observed:** `ADD 1` without `ON SIZE ERROR` takes 999 to 000.
- **Impact:** theoretical (999 annual renewals), but it is real truncation.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** Use an integer count. Above 999 the API rejects the renewal rather than wrapping, because the legacy record can't hold it.
- **Implemented by:** AB-526.

### Q-19

**UNDWRT risk score is unsigned**

- **Location:** `WS-RISK-SCORE PIC 9(04)` at `cobol/programs/UNDWRT.cbl:33`; `cobol/programs/UNDWRT.cbl:121-134`.
- **Observed:** base = (900 − credit) / 2, truncated. A preferred tier subtracts 100. When the base is below 100 (credit score ≥ 701), the result is negative and the unsigned field stores its absolute value: credit 850 gives base 25, then −75, stored as 75. A credit score above 900 also goes negative and is stored as the absolute value.
- **Impact:** the stored `RISK_SCORE` is higher than intended for the best customers. The decision does not change, because the result is still below 300.
- **Owner:** Underwriting.
- **Decision:** **Preserve** (use `CobolArithmetic.storeUnsigned`).
- **Rationale:** the decision is unaffected, and parity of `RISK_SCORE` matters for reports and extracts (DAILY-EXTRACT selects `RISK_SCORE`).
- **Implemented by:** AB-525.

### Q-20

**Risk score ≥ 1000 truncated in the policy record**

- **Location:** `cobol/programs/UNDWRT.cbl:209` moves `WS-RISK-SCORE` (9(4)) to `POLICY-RISK-SCORE` (9(3), `cobol/copybooks/POLICY-RECORD.cpy:40`). The table gets the full value (`cobol/programs/UNDWRT.cbl:226-233`).
- **Observed:** the maximum reachable score is 1750 (400+200+150+200+200+100+200+300). A score of 1050 shows as 050 on the result map (`cobol/programs/UNDWRT.cbl:245-251`) and in the decision queue message (`cobol/programs/UNDWRT.cbl:236-243`). DB2 stores 1050.
- **Impact:** consumers of the queue message see a low-risk score on a declined policy.
- **Owner:** Underwriting.
- **Decision:** **Fix.** Events and APIs carry the full score. If AB-528's legacy bridge rebuilds the 125-byte record, it has to truncate the score to 3 digits there, and must document that.
- **Implemented by:** AB-525, AB-528.

### Q-21

**Loss ratio overflow**

- **Location:** `WS-LOSS-RATIO PIC S9(03)V99` at `cobol/programs/UNDWRT.cbl:40`; `cobol/programs/UNDWRT.cbl:152-159`.
- **Observed:** incurred / premium × 100 is truncated to 3 integer digits (no `ON SIZE ERROR`). A loss ratio of 1050% is stored as 50.00, which fails the `> 80` test, so the +200 surcharge is not added.
- **Impact:** the worst loss histories can score lower than moderate ones.
- **Owner:** Underwriting.
- **Decision:** **Fix.** Use the full-precision ratio.
- **Implemented by:** AB-525.

### Q-22

**Accumulation reason overwritten**

- **Location:** `cobol/programs/UNDWRT.cbl:181-187` writes "ACCUMULATION LIMIT EXCEEDED FOR BRANCH …", then every branch of the EVALUATE at `cobol/programs/UNDWRT.cbl:191-208` overwrites `WS-DECISION-REASON`.
- **Observed:** the stored `DECISION_REASON` never mentions accumulation.
- **Impact:** an underwriter can't see why a referral happened.
- **Owner:** Underwriting.
- **Decision:** **Fix.** Keep all reasons (decision text plus contributing factors).
- **Implemented by:** AB-525.

### Q-23

**Decision codes RS/RM are not in the copybook**

- **Location:** `cobol/programs/UNDWRT.cbl:34-38` (AP/RS/RM/DC); `cobol/copybooks/POLICY-RECORD.cpy:35-39` (AP/PN/DC/RF); moved to `UW_STATUS` at `cobol/programs/UNDWRT.cbl:210` and `cobol/programs/UNDWRT.cbl:228`.
- **Observed:** `POLICIES.UW_STATUS` holds RS and RM, which the copybook's 88-levels don't define. RF is never written by any program.
- **Impact:** a consumer that decodes against the copybook (for example `UW-REFERRED`) misses every referral.
- **Owner:** Underwriting.
- **Decision:** **Preserve.** The UW status enum includes AP, PN, DC, RF, RS and RM.
- **Implemented by:** AB-521 (types), AB-525.

### Q-24

**No program activates a policy**

- **Location:** POLNEW inserts `POLICY_STATUS = 'PN'` (`cobol/programs/POLNEW.cbl:151`). UNDWRT changes only `UW_STATUS`/`RISK_SCORE` (`cobol/programs/UNDWRT.cbl:226-233`). No program in this repository sets `POLICY_STATUS = 'AC'` for a new policy, or sets CN, EX or LP at all.
- **Observed:** PN→AC, expiry (EX) and lapse (LP) transitions happen outside this repository or by hand.
- **Impact:** a policy that is only in PN is not premium-rated (PREMBAT reads only AC, `cobol/programs/PREMBAT.cbl:118-127`), but it is in the DAILY-EXTRACT (`jcl/DAILY-EXTRACT.jcl:53`).
- **Owner:** PAS product owner.
- **Decision:** **Preserve (open question).** Do not invent an activation rule. OQ-01 asks where these transitions happen.
- **Implemented by:** AB-525 (records status PN), follow-up after OQ-01.

### Q-25

**Repeat underwriting decisions on the same day**

- **Location:** PK `(POLICY_NUMBER, DECISION_DATE)` at `sql/ddl/create-tables.sql:140-141`; INSERT at `cobol/programs/UNDWRT.cbl:214-224` with no SQLCODE check; UPDATE at `cobol/programs/UNDWRT.cbl:226-233` runs regardless; `UNDERWRITER_ID = 'SYSTEM'` at `cobol/programs/UNDWRT.cbl:223`.
- **Observed:** a second PUWR on the same day fails the audit insert with a duplicate key, but `POLICIES` still takes the new decision. `OVERRIDE_REASON`/`OVERRIDE_BY` (`sql/ddl/create-tables.sql:137-138`) are not written by any program here.
- **Impact:** the decision audit trail has gaps, and human decisions can't be attributed to anyone.
- **Owner:** Underwriting.
- **Decision:** **Fix.** Every decision is stored with a timestamp and the acting user from the JWT `sub`, and override fields are captured.
- **Implemented by:** AB-525.

### Q-26

**Nullable columns selected without indicator variables**

- **Location:** `cobol/programs/UNDWRT.cbl:96-110` selects `CREDIT_SCORE` and `RISK_TIER`, both nullable (`sql/ddl/create-tables.sql:167-168`). `cobol/programs/POLQRY.cbl:107-119` selects nullable `LAST_NAME`, `FIRST_NAME`, `COMPANY_NAME`, `PHONE` and `EMAIL`. Neither uses indicator variables.
- **Observed:** a NULL value gives SQLCODE -305. In UNDWRT this produces "POLICY/CUSTOMER DATA NOT FOUND" (`cobol/programs/UNDWRT.cbl:111-114`), so the "no credit score → 400" branch (`cobol/programs/UNDWRT.cbl:121-126`) only runs for 0. In POLQRY the error is not checked. Individual customers have NULL `COMPANY_NAME` and commercial customers have NULL names, so the customer read fails every time.
- **Impact:** underwriting can't decide on a policy whose holder has no credit score.
- **Owner:** Mainframe team (DB2), with Underwriting.
- **Decision:** **Fix.** NULL credit score → base 400 (the coded intent), and NULL tier → standard.
- **Implemented by:** AB-525 (underwriting), AB-524 (inquiry).

### Q-27

**Hardcoded underwriting rules; catastrophe, terrorism and cyber scoring missing**

- **Location:** bands at `cobol/programs/UNDWRT.cbl:190-208` (<300 AP, <600 RS, <800 RM, otherwise DC); claims thresholds at `cobol/programs/UNDWRT.cbl:144-149`; limit thresholds at `cobol/programs/UNDWRT.cbl:162-167`; $500M branch accumulation at `cobol/programs/UNDWRT.cbl:179-187`; `WS-ZONE-RISK-FACTOR` declared at `cobol/programs/UNDWRT.cbl:44` and never used; header modification history at `cobol/programs/UNDWRT.cbl:9-11`.
- **Observed:** the modification history lists terrorism (2004), catastrophe zone (2011) and cyber (2018) scoring, but none of them is in the code. The accumulation sum counts only `AC` policies (`cobol/programs/UNDWRT.cbl:172-178`), so the policy being underwritten (PN) is excluded.
- **Owner:** Underwriting.
- **Decision:** **Preserve (configurable).** Thresholds become configuration with these defaults. No catastrophe, terrorism or cyber scoring until Underwriting asks for it (OQ-08).
- **Implemented by:** AB-525.

### Q-28

**Dates as PIC 9(8) against DB2 DATE columns**

- **Location:** copybook dates are PIC 9(8) YYYYMMDD (`cobol/copybooks/POLICY-RECORD.cpy:25-26`, `cobol/copybooks/POLICY-RECORD.cpy:33`, `cobol/copybooks/COVERAGE-RECORD.cpy:26-27`, `cobol/copybooks/PREMIUM-RECORD.cpy:13-14`) and are used as host variables for DATE columns (for example `cobol/programs/POLRNW.cbl:89-98`, `cobol/programs/POLEND.cbl:87-97`, `cobol/programs/UNDWRT.cbl:220`, `cobol/programs/PREMBAT.cbl:104-110`). POLNEW's own DCLGEN uses X(10) (`cobol/programs/POLNEW.cbl:47-48`). `EXEC CICS ASKTIME ABSTIME(WS-CURRENT-DATE)` writes an S9(15) COMP-3 value into a PIC 9(8) field (for example `cobol/programs/POLNEW.cbl:86-93`). That works only because both are 8 bytes.
- **Observed:** standard DB2 needs a character host variable for DATE. A numeric one gives SQLCODE -303/-401, unless a site date exit or precompiler option is in place (OQ-04). `POLICY-LAST-UPDATED PIC 9(8)` (`cobol/copybooks/POLICY-RECORD.cpy:47`) maps a TIMESTAMP column.
- **Owner:** Mainframe team (DB2).
- **Decision:** **Fix.** `LocalDate`/`OffsetDateTime` in Java. Copybook codecs convert YYYYMMDD exactly where records are exchanged.
- **Implemented by:** AB-521 (codecs and data model).

### Q-29

**Queue writes: failures ignored, and names longer than CICS allows**

- **Location:** POLNEW `cobol/programs/POLNEW.cbl:31-32`, `cobol/programs/POLNEW.cbl:240-253` (the comment says "non-fatal", but nothing is logged); UNDWRT `cobol/programs/UNDWRT.cbl:45-46`, `cobol/programs/UNDWRT.cbl:236-243` (`RESP` captured but not checked).
- **Observed:** both programs pass a 48-byte field holding an MQ-style name (`ACME.PAS.UNDERWRITING.REQUEST` / `.DECISION`) to `WRITEQ TD QUEUE()`, which takes a 1–4 character destination ID. Unless CICS translates the name some other way, both resolve to TDQ `ACME`, and the request/decision distinction would then depend on the TDQ-to-MQ bridge definition, which is not in the repo (M-04, OQ-03). The policy row is already inserted when the queue write fails, and there is no SYNCPOINT or rollback.
- **Impact:** new policies can silently miss their underwriting referral, and decisions can miss FileNet document creation.
- **Owner:** Mainframe team (CICS/MQ).
- **Decision:** **Fix.** A transactional outbox: the event is written in the same DB transaction (topics `pas.underwriting.events.v1`, `pas.document.requests.v1`).
- **Implemented by:** AB-528 (AB-525 writes the outbox rows).

### Q-30

**POLNEW validation reports only the last error**

- **Location:** `cobol/programs/POLNEW.cbl:108-134`.
- **Observed:** each check overwrites `WS-ERROR-MSG`, so only the last failing check is shown. The policyholder SELECT runs even when the ID is blank. A RECEIVE failure (`cobol/programs/POLNEW.cbl:103-105`) does not skip validation.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** Return every validation error (RFC 7807 field errors).
- **Implemented by:** AB-525.

### Q-31

**New-business premium entered by the operator; API flag always N**

- **Location:** `cobol/programs/POLNEW.cbl:155` copies `POLICY-TOTAL-PREMIUM` from the map; `cobol/programs/POLNEW.cbl:162` sets `POL-API = 'N'`, although the change history says "Added API flag for REST facade" (`cobol/programs/POLNEW.cbl:11`).
- **Observed:** nothing rates the policy at bind time. PREMBAT rates it only after it becomes `AC` (Q-24).
- **Owner:** Actuarial / PAS product owner.
- **Decision:** **Preserve.** The premium is accepted from the request, and `api_flag` = `N` for policies created by new business unless the product owner decides otherwise (OQ-09).
- **Implemented by:** AB-525.

### Q-32

**No object-level authorization**

- **Location:** none of the five CICS programs checks the user against agent or branch (no `QUERY SECURITY`, and no use of `EIBTRMID` or user ID): `cobol/programs/POLQRY.cbl:78-98`, `cobol/programs/POLRNW.cbl:89-98`, `cobol/programs/POLEND.cbl:87-97`, `cobol/programs/UNDWRT.cbl:96-110`, `cobol/programs/POLNEW.cbl:148-163` (agent/branch taken from input). The facade has no authentication and turns actuator security off (`java-facade/src/main/resources/application.properties:32-34`).
- **Observed:** anyone allowed to run the transaction ID can read or change any policy, and new business can be booked under any agent or branch. Only transaction-level RACF applies, and those profiles are not in the repo (M-07). Related existing tickets: AB-364 (renewal IDOR) and AB-365 (facade has no auth/BOLA).
- **Owner:** Security.
- **Decision:** **Fix.** JWT scopes plus `agent_id`/`branch_codes`/`book` entitlements enforced in SQL. Objects the caller is not entitled to return 404.
- **Implemented by:** AB-524 (inquiry), AB-525 (new business/UW), AB-526 (renewal/endorsement).

### Q-33

**POLQRY reads data it never shows**

- **Location:** customer read at `cobol/programs/POLQRY.cbl:107-119`; coverages read into a 20-entry table (`cobol/programs/POLQRY.cbl:32-38`, `cobol/programs/POLQRY.cbl:121-151`); only `POLICY-RECORD` is sent (`cobol/programs/POLQRY.cbl:153-160`). `PREMIUM-RECORD` is copied in (`cobol/programs/POLQRY.cbl:30`) but never read.
- **Observed:** unless the BMS map POLQDET (not in the repo, M-02) pulls other fields some other way, the screen shows only the policy header. Coverages after the 20th are dropped silently, and a FETCH error is treated as end of data.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The inquiry API returns the policy and paged coverages, and returns customer data only to entitled callers, with PII masked.
- **Implemented by:** AB-524.

### Q-34

**PREMBAT report-open failure ends RC 0**

- **Location:** `cobol/programs/PREMBAT.cbl:85-92`.
- **Observed:** `MOVE 16 TO WS-RETURN-CODE` and then `STOP RUN`. `RETURN-CODE` is set only in 9999-TERMINATE (`cobol/programs/PREMBAT.cbl:257`), which this path never reaches, so the step ends RC 0 having done nothing.
- **Impact:** a run that did no work looks successful.
- **Owner:** Mainframe team (batch).
- **Decision:** **Fix.** A failed job exits non-zero (Spring Batch exit status FAILED).
- **Implemented by:** AB-523.

### Q-35

**PREMIUM-BATCH alerting is backwards**

- **Location:** `jcl/PREMIUM-BATCH.jcl:42-48`.
- **Observed:** STEP020 is `IEFBR14` (does nothing), even though the comment says "send email". STEP030 has `COND=(4,LT,STEP010)`, so it is skipped when 4 < RC, and therefore runs on RC 0 and RC 4. It sends "PREMIUM BATCH COMPLETED WITH WARNINGS" to TSO user PASADMIN on clean runs too, and sends nothing on RC ≥ 8 or an abend.
- **Owner:** Mainframe team (batch).
- **Decision:** **Fix.** Job outcome metrics plus alerts on FAILED, or on COMPLETED with errors.
- **Implemented by:** AB-523.

### Q-36

**PREMBAT unit of work and output shape**

- **Location:** no COMMIT anywhere in `cobol/programs/PREMBAT.cbl`, so the whole book is one unit of work, committed at normal termination of the DSN RUN (`jcl/PREMIUM-BATCH.jcl:32-36`). One row per policy with `COVERAGE_SEQ = 1` (`cobol/programs/PREMBAT.cbl:192`). `POLICIES.TOTAL_PREMIUM` is never updated: PREMBAT writes only PREMIUMS.
- **Observed:** an abend late in the run rolls back everything. Locks are held for the whole run (the README quotes about 4 hours). The policy premium shown by POLQRY, the facade and the extracts is not the one PREMBAT calculated.
- **Owner:** Actuarial (output shape), mainframe team (operations).
- **Decision:** **Preserve** the outputs: one PREMIUMS row per policy with `COVERAGE_SEQ = 1`, and `POLICIES.TOTAL_PREMIUM` unchanged. **Fix** the commit and restart behaviour: chunked commits, restartable.
- **Rationale:** downstream consumers read `POLICIES.TOTAL_PREMIUM`. Changing it is a product decision (OQ-07).
- **Implemented by:** AB-523.

### Q-37

**Extracts sent by plaintext FTP**

- **Location:** `jcl/DAILY-EXTRACT.jcl:83-96` (host `CLAIMSRV.ACME.LOCAL`, user `ACMEFTP`, PUT to fixed names under `/claims/import/`); `jcl/MONTHLY-EXPOSURE.jcl:60-71` (host `ACTUARIAL.ACME.LOCAL`, fixed name `/actuarial/data/exposure_monthly.csv`); change-history claim "Added encryption step (regulatory)" at `jcl/DAILY-EXTRACT.jcl:11`.
- **Observed:** the job has no encryption step. The input stream has no password line, so the credential presumably comes from a NETRC dataset or from somewhere else (OQ-02). Remote files are overwritten in place, and there is no checksum or completion marker, so the consumer can read a partial file. The README lists the unencrypted transfer as a known limitation.
- **Impact:** policy and customer data are sent in clear text, which is a regulatory risk.
- **Owner:** Security (with the Claims and Actuarial owners for the receiving side).
- **Decision:** **Fix.** SFTP with key auth and a pinned host key, a SHA-256 checksum file and a `.done` marker. Remote paths and file names are kept so consumers need no change.
- **Implemented by:** AB-527.

### Q-38

**DAILY-EXTRACT selection rules**

- **Location:** `jcl/DAILY-EXTRACT.jcl:46-56` (policies with status AC/PN and `LAST_UPDATED >= CURRENT DATE - 1 DAY`); `jcl/DAILY-EXTRACT.jcl:74-81` (all AC coverages, no date filter).
- **Observed:** the policy file is a delta. It covers yesterday 00:00 up to the 23:00 run, so the windows of consecutive days overlap by about 23 hours, and a missed run loses changes older than yesterday. The coverage file is a full snapshot. Cancelled, expired and lapsed policies never appear, so Claims never learns about a cancellation from this feed. PREMBAT does not update `POLICIES` (Q-36), so recalculated premiums never trigger a delta row.
- **Owner:** Claims system owner.
- **Decision:** **Preserve** the selection for parity, with the delta window anchored to the run date as in legacy. Any change (for example, including CN) is agreed with Claims (OQ-10).
- **Implemented by:** AB-527.

### Q-39

**Customer extract and Broker Portal refresh are documented but not implemented**

- **Location:** `jcl/DAILY-EXTRACT.jcl:14-21` lists `ACME.PAS.EXTRACT.CUSTOMER.DAILY` and a Broker Portal linked-server refresh. No step produces either.
- **Owner:** Claims system owner and Broker Portal owner.
- **Decision:** **Preserve (open question).** No customer file is produced. OQ-11 asks whether one is produced somewhere else.
- **Implemented by:** AB-527 (extracts), AB-528 (Broker Portal reads).

### Q-40

**Fixed dataset names allocated as NEW**

- **Location:** `jcl/DAILY-EXTRACT.jcl:36-39`, `jcl/DAILY-EXTRACT.jcl:65-68` (fixed DSNs, `DISP=(NEW,CATLG,DELETE)`); `jcl/PREMIUM-BATCH.jcl:28-31` (date-qualified, so only a same-day rerun collides).
- **Observed:** no step deletes these datasets and they are not GDGs, so the second daily run fails allocation with a duplicate data set name, unless housekeeping outside this repo deletes them (OQ-12). A same-day PREMIUM-BATCH rerun fails the same way.
- **Owner:** Mainframe team (batch).
- **Decision:** **Fix.** The new jobs write per-run files and replace remote files atomically.
- **Implemented by:** AB-523, AB-527.

### Q-41

**Extract record layout undefined; DSNTEP2 output goes to SYSPRINT**

- **Location:** `jcl/DAILY-EXTRACT.jcl:31-56` (STEP010 allocates POLOUT LRECL 200), `jcl/DAILY-EXTRACT.jcl:61-81` (STEP020 allocates COVOUT LRECL 150 and has no SYSPRINT DD), `jcl/MONTHLY-EXPOSURE.jcl:26-58` (EXPOUT LRECL 300).
- **Observed:** standard DSNTEP2 writes a formatted report (headings, column titles, page breaks) to SYSPRINT. It does not write to POLOUT, COVOUT or EXPOUT. STEP010 runs DSNTEP2 from `ACME.PAS.LOADLIB` (`jcl/DAILY-EXTRACT.jcl:42-43`), so it may be a site-modified copy. As written, the extract datasets would be empty and STEP020 has nowhere to write its output. The file the consumers actually receive cannot be derived from this repository.
- **Owner:** Mainframe team (batch).
- **Decision:** **Fix.** The new extracts have explicit versioned layouts (fixed LRECL 200 and 150, and CSV maximum record length 300) reconstructed from the schema and JCL clues. They are not proven byte-parity layouts. Compare against captured production files, not the JCL, when samples become available (OQ-13).
- **Implemented by:** AB-527 (layouts and comparer); parity remains pending production samples.

### Q-42

**MONTHLY-EXPOSURE counts coverages as policies**

- **Location:** `jcl/MONTHLY-EXPOSURE.jcl:40-57`.
- **Observed:** `COUNT(*) AS POLICY_COUNT` over the POLICIES × COVERAGES join counts coverage rows. A HOM policy with 2 coverages counts as 2. The grouping includes `RATING_TERRITORY`/`CLASS_CODE`, which POLNEW never populates (Q-10), so new business groups under NULL. The output is called `.csv` but is DSNTEP2 output (Q-41). It is a point-in-time snapshot at 02:00 on the 1st, not an as-of month-end view, and the remote file is overwritten every month.
- **Owner:** Actuarial.
- **Decision:** **Preserve** the counting and grouping semantics (Actuarial models are calibrated on them), with a column comment saying it is a coverage count. **Fix** the transport and file naming under Q-37.
- **Implemented by:** AB-527.

### Q-43

**Facade seed data outside COBOL domains**

- **Location:** `java-facade/src/main/resources/data.sql` (policy numbers `POL-00000001`…, type `CGL`, risk tiers `A`/`B`, customer `C000000003` with no credit score).
- **Observed:** the values don't match the copybook 88-levels (`cobol/copybooks/POLICY-RECORD.cpy:13-18`, `cobol/copybooks/CUSTOMER-RECORD.cpy:33-36`) or POLNEW's number format (Q-08). Whether production holds such values is unknown (OQ-14).
- **Owner:** Data migration lead.
- **Decision:** **Preserve.** Migration and the new domain model accept unknown codes, logged and counted in reconciliation, and do not reject them.
- **Implemented by:** AB-521 (types), AB-529 (migration).

### Q-44

**CICS RECEIVE failure leaves the performed flow running**

- **Location:** `cobol/programs/UNDWRT.cbl:91-117` sets an error, jumps to `2000-EXIT`, then reaches the following paragraphs; `cobol/programs/UNDWRT.cbl:57-70` contains the caller. `cobol/programs/POLRNW.cbl:85-104` has the same `GO TO 2000-EXIT` pattern, with its caller at `cobol/programs/POLRNW.cbl:49-64`.
- **Observed:** the AB-520 GnuCOBOL goldens show that UNDWRT still emits a decision, policy update and notification alongside the receive error. POLRNW still emits policy and coverage updates and a confirmation alongside its error.
- **Impact:** an online transaction can write or notify after CICS failed to receive its request.
- **Owner:** PAS product owner / Underwriting.
- **Decision:** **Fix.** A failed receive stops processing and returns the error.
- **Found by:** AB-520. The decision is proposed pending product-owner and mainframe-team sign-off.
- **Implemented by:** AB-525, AB-526.

### Q-45

**Negative credit loses its sign in the unsigned customer field**

- **Location:** `CUST-CREDIT-SCORE PIC 9(03)` at `cobol/copybooks/CUSTOMER-RECORD.cpy:32`; the UNDWRT SELECT assigns the signed host variable at `cobol/programs/UNDWRT.cbl:104`.
- **Observed:** the AB-520 harness models a signed fixture value of -5 being moved into the unsigned field; the COBOL golden scores it as 5 and produces risk score 447. The actual DB2 host-variable conversion is unverified on the mainframe.
- **Impact:** a nonpositive credit score can be treated as a positive score rather than as missing credit information.
- **Owner:** Underwriting.
- **Decision:** **Fix.** Treat scores less than or equal to zero as no score, with base 400.
- **Found by:** AB-520.
- **Implemented by:** AB-525.

### Q-46

**Extract ordering depends on DB2 EBCDIC collation and leaves ties unordered**

- **Location:** `jcl/DAILY-EXTRACT.jcl:53-56`, `jcl/DAILY-EXTRACT.jcl:78-81`, and `jcl/MONTHLY-EXPOSURE.jcl:53-57`.
- **Observed:** DB2 compares character values in its EBCDIC code page, while PostgreSQL's `"C"` collation compares bytes in the extract's ASCII-compatible character set. Mixed letters and digits (including branch codes) may therefore sort differently. The legacy exposure order specifies only policy type and branch, leaving rows tied on those keys in an unspecified order.
- **Impact:** byte-for-byte comparisons can differ or vary between runs even when the selected rows and values are the same.
- **Owner:** Mainframe team (batch), with Actuarial and Claims for sample validation.
- **Decision:** **Fix.** Order with PostgreSQL byte-order `"C"` collation and all grouping keys as deterministic tiebreakers. Validate the resulting order against production samples before cutover.
- **Implemented by:** AB-527.

### Q-47

**PREMRPT premium fields are not printable text**

- **Location:** `cobol/programs/PREMBAT.cbl:226-243`.
- **Observed:** the report line moves the packed-decimal `WS-TOTAL-PREMIUM` directly into a display record. The resulting bytes are not a stable textual amount and cannot be compared or consumed as a readable report value.
- **Impact:** report consumers cannot reliably parse the premium amount from the legacy line.
- **Owner:** Mainframe team (batch).
- **Decision:** **Fix.** Render the amount as nine integer digits, a decimal point, and two fractional digits using locale-independent formatting, for example `000000904.75`.
- **Implemented by:** AB-523 (`PremiumReportFormatter`).

### Q-49

**POLRNW and POLEND arithmetic wraps instead of failing**

- **Location:** `cobol/programs/POLRNW.cbl` 4000-CALCULATE-NEW-PREMIUM (`COMPUTE` without `ON SIZE ERROR`, rate change divides by the old premium); `cobol/programs/POLEND.cbl` 5000 (`MAX(ENDORSEMENT_SEQ) + 1` into `PIC 9(05)`).
- **Observed:** the AB-520 goldens show a 999,999,999.99 premium renewing at 49,999,999.98 (the uplift loses its high-order digit), a zero premium renewing at 0.00 after a division by zero, and endorsement sequence 99,999 wrapping to 0.
- **Impact:** a renewal can silently cut the premium, and an endorsement can collide with an existing ENDORSEMENTS key.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The renewal and endorsement APIs reject these cases with 422 (`RENEWAL_PREMIUM_INVALID`, `ENDORSEMENT_SEQUENCE_LIMIT`) instead of writing a wrapped value.
- **Found by:** AB-526.
- **Implemented by:** AB-526.

### Q-50

**POLEND accepts zero-length terms and dates outside the term**

- **Location:** `cobol/programs/POLEND.cbl:114-129` (days in term and days remaining, unsigned, divided without a zero check).
- **Observed:** when the effective date equals the expiry date the factor divides by zero, and a date after expiry loses the sign of the negative day count (AB-520 fixtures `effective-equals-expiry`, `current-after-expiry`). The endorsement is still written.
- **Impact:** endorsements are recorded against terms that do not exist or have ended.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** A zero-length or inverted term and an endorsement date outside [effective, expiry] are rejected with 422. The first and last days of the term are inside it.
- **Found by:** AB-526.
- **Implemented by:** AB-526.

### Q-51

**POLEND posts whatever WS-PREMIUM-ADJUST holds**

- **Location:** `WS-PREMIUM-ADJUST` at `cobol/programs/POLEND.cbl:32` is never initialised or assigned; it is inserted and added to `TOTAL_PREMIUM` (`cobol/programs/POLEND.cbl:140-166`).
- **Observed:** the harness seeds working storage to show that any residue (10.00, 125.50, -1200.00) reaches ENDORSEMENTS and POLICIES.
- **Impact:** a stale value changes the premium. Q-11 already decides the intended adjustment is 0.00.
- **Owner:** PAS product owner.
- **Decision:** **Fix.** The adjustment is always initialised to 0.00, as Q-11 requires, whatever the request or prior state holds.
- **Found by:** AB-526.
- **Implemented by:** AB-526.
