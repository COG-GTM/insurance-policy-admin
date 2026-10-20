# PAS quirk register (AB-517)

Every behaviour of the legacy PAS that looks wrong, surprising or undocumented, together with
the business decision on whether the Java platform (`pas-platform/`) **preserves** it or
**fixes** it. Line references are to this repository at the time of writing.

| | |
|---|---|
| Epic | AB-516 PAS modernization |
| Ticket | AB-517 Discovery: behaviour inventory |
| Status of every decision | **Proposed — pending PO sign-off** |
| Reviews | Pending: Product Owner (PAS). Pending: Mainframe team lead (mainframe-ops@acme.com). |

Related documents: [inventory](inventory.md), [integrations](integrations.md),
[missing artifacts](missing-artifacts.md), [open questions](open-questions.md),
[parity harness](../../parity/README.md).

## How to use this register

- **IDs are stable.** Each quirk has a heading `Q-NN`, so its anchor is `#q-nn`
  (for example `docs/modernization/quirk-register.md#q-01`). Never renumber or reuse an ID;
  retire an item by setting its status to *Withdrawn* and keep the heading.
- **Preserve** means the Java port reproduces the COBOL result byte-for-byte (cent level) and
  the parity golden stays as it is. **Fix** means the Java result intentionally differs. Each
  fix that changes a golden value needs a `parity/known-differences.json` entry whose
  `decision` is this file's path plus the anchor, for example
  `docs/modernization/quirk-register.md#q-01`. The `ParityHarness` only accepts
  `docs/modernization/...` paths or AB-517 links in `decision`.
- Decisions come from the epic's strategy: port at parity first; fix only what the business
  explicitly signs off. Rating moves at parity; table-driven factors are the only intended
  rating change, and they stay behind a flag that is off by default.
- *Golden* refers to the committed `parity/programs/<prog>/golden.csv` row that pins the
  behaviour. *No golden* means the program is not yet in the parity harness (POLNEW, POLQRY,
  JCL jobs); those items have a follow-up in [open questions](open-questions.md).

Owner roles: **PO** = PAS Product Owner, **UW** = Underwriting business owner,
**ACT** = Actuarial/pricing owner, **CLM** = Claims team, **MF** = Mainframe team lead,
**OPS** = Batch operations, **SEC** = Security, **DBA** = DB2 DBA team.

## Summary

| ID | Program / location | Behaviour | Decision | Owner | Target | Status |
|----|--------------------|-----------|----------|-------|--------|--------|
| [Q-01](#q-01) | POLRNW `6000-CREATE-RENEWAL-TERM` L148-150 | Expiry = YYYYMMDD + 10000; 20240229 renews to invalid 20250229 | **Fix** | PO | AB-526 | Proposed — pending PO sign-off |
| [Q-02](#q-02) | POLRNW `3000-CHECK-RENEWAL-ELIGIBILITY` L111-119 | Open-claims COUNT is queried but never checked | **Fix** | PO + CLM | AB-526 | Proposed — pending PO sign-off |
| [Q-03](#q-03) | PREMBAT `2000-LOAD-RATING-TABLES` L102-115, `3200` L166-176 | Territory cursor opened and closed, never fetched; territory, class and experience factors are all 1.00 | **Preserve** (real factors = flag-guarded follow-up, default off) | ACT | AB-522 / AB-523 | Proposed — pending PO sign-off |
| [Q-04](#q-04) | POLRNW `5000-APPLY-RATE-CAP` L29-30, L133-141 | Flat 15% renewal cap, not state-specific (and unreachable while uplift is 5%) | **Preserve** | PO + ACT | AB-526 | Proposed — pending PO sign-off |
| [Q-05](#q-05) | POLRNW `4000-CALCULATE-NEW-PREMIUM` L122-131 | Renewal premium = old premium x 1.05, hardcoded | **Preserve** | ACT | AB-526 | Proposed — pending PO sign-off |
| [Q-06](#q-06) | POLRNW L126-130, L137-138 | COMPUTE without ROUNDED truncates (1234.57 → 1296.29, 5.00% → 4.99) | **Preserve** | ACT | AB-526 | Proposed — pending PO sign-off |
| [Q-07](#q-07) | POLRNW L144, L148-150 | PIC overflows: renewal count 999 → 0; expiry 99991231 → 1231 | **Preserve** | PO | AB-526 | Proposed — pending PO sign-off |
| [Q-08](#q-08) | POLRNW L128-130 | Zero premium divides by zero; no abend, rate change 0.00 | **Preserve** | PO | AB-526 | Proposed — pending PO sign-off |
| [Q-09](#q-09) | POLRNW `0000-MAIN-LOGIC` L55-60, L166-169 | Failed UPDATE sends error text **and** confirmation; coverages still updated | **Fix** | PO + MF | AB-526 | Proposed — pending PO sign-off |
| [Q-10](#q-10) | POLEND `3000-VALIDATE-ENDORSEMENT` L33-38, L108-111 | `WS-ENDORSEMENT-TYPE` is never populated | **Preserve** | PO | AB-526 | Proposed — pending PO sign-off |
| [Q-11](#q-11) | POLEND `5000-APPLY-ENDORSEMENT` L32, L158-166 | `WS-PREMIUM-ADJUST` is never computed; pro-rata factor unused | **Preserve** | PO | AB-526 | Proposed — pending PO sign-off |
| [Q-12](#q-12) | POLEND `4000-CALCULATE-PRORATA` L29-30, L114-129 | PIC 9(3) day counts: 1096-day term → 96; -14 days → +14 | **Preserve** | PO | AB-526 | Proposed — pending PO sign-off |
| [Q-13](#q-13) | POLEND L117-122 | Invalid date (e.g. 20250229 from Q-01) → `INTEGER-OF-DATE` returns 0 → garbage factor | **Preserve** (behaviour on z/OS to be confirmed) | MF | AB-526 | Proposed — pending PO sign-off |
| [Q-14](#q-14) | POLEND L152-166, L48-64 | Failed ENDORSEMENTS INSERT still updates the policy, writes audit and sends confirmation | **Fix** | PO + MF | AB-526 | Proposed — pending PO sign-off |
| [Q-15](#q-15) | POLEND whole program | Endorsement types are never applied (cancel does not cancel; coverage/limit/address unchanged) | **Preserve** | PO | AB-526 | Proposed — pending PO sign-off |
| [Q-16](#q-16) | UNDWRT `3000-CALCULATE-RISK-SCORE` L33, L121-134 | Unsigned PIC 9(4): 25 - 100 → 75; credit > 900 → absolute value | **Preserve** | UW | AB-525 | Proposed — pending PO sign-off |
| [Q-17](#q-17) | UNDWRT `5000-RENDER-DECISION` L209; POLICY-RECORD L40 | `POLICY-RISK-SCORE` PIC 9(3) stores score mod 1000 (1150 → 150) in the record sent on the TDQ | **Preserve** | UW | AB-525 / AB-528 | Proposed — pending PO sign-off |
| [Q-18](#q-18) | UNDWRT L40, L152-159 | Loss ratio PIC S9(3)V99 truncates (200000% → 0.00), skipping the +200 penalty | **Preserve** | UW | AB-525 | Proposed — pending PO sign-off |
| [Q-19](#q-19) | UNDWRT `4000-CHECK-ACCUMULATION` L181-186, L190-208 | Accumulation-limit reason text is always overwritten by the decision text | **Preserve** | UW | AB-525 | Proposed — pending PO sign-off |
| [Q-20](#q-20) | UNDWRT `6000-WRITE-DECISION` L214-233; DDL L140-141 | Second decision on the same day hits PK (POLICY_NUMBER, DECISION_DATE); error ignored, policy still updated | **Fix** | UW + DBA | AB-521 / AB-525 | Proposed — pending PO sign-off |
| [Q-21](#q-21) | UNDWRT L18-22, L34-38, L181, L190-208 | Hardcoded thresholds and $500M branch limit; writes RS/RM while copybook only knows RF | **Preserve** | UW | AB-525 | Proposed — pending PO sign-off |
| [Q-22](#q-22) | PREMBAT `3300-WRITE-PREMIUM-RECORD` L192, L202-224; DDL L103-104 | PREMIUMS PK collides on any rerun in the same term (incl. same-day rerun); rows counted as errors | **Fix** (rerun replaces) | OPS + ACT | AB-523 | Proposed — pending PO sign-off |
| [Q-23](#q-23) | PREMBAT `3200-CALCULATE-PREMIUM` L70-72, L151-187 | Base rate by policy type only; flat 3.5% tax; flat $25 surcharge; COVERAGE_SEQ always 1; POLICIES/COVERAGES premiums not updated | **Preserve** | ACT | AB-522 / AB-523 | Proposed — pending PO sign-off |
| [Q-24](#q-24) | PREMBAT L227-231 | Report line STRINGs a raw COMP-3 field, printing packed bytes | **Fix** | OPS | AB-523 | Proposed — pending PO sign-off |
| [Q-25](#q-25) | PREMBAT `1000-INITIALIZE` L88-92 | Report open failure does STOP RUN before RETURN-CODE is set: job ends RC 0, not 16 | **Fix** | OPS | AB-523 | Proposed — pending PO sign-off |
| [Q-26](#q-26) | `jcl/PREMIUM-BATCH.jcl` L42-48 | "Completed with warnings" sent for RC 0-4; nothing sent on failure; STEP020 is a no-op | **Fix** | OPS | AB-523 | Proposed — pending PO sign-off |
| [Q-27](#q-27) | POLNEW `4000-GENERATE-POLICY-NUM` L136-146 | 'POL' + PIC 9(10) STRINGed into X(12) drops the last digit: 10 sequence values share one policy number | **Fix** (keep `POL`+9-digit format, guarantee uniqueness) | PO + DBA | AB-525 | Proposed — pending PO sign-off |
| [Q-28](#q-28) | POLNEW `5000-INSERT-POLICY` L148-189, `6100-WRITE-COVERAGE` L223-238 | Effective/expiry/inception dates and coverage amounts are never moved to host variables; LIF/HLT get no coverages | **Fix** | PO + MF | AB-525 | Proposed — pending PO sign-off |
| [Q-29](#q-29) | POLNEW `3000-VALIDATE-INPUT` L108-134, L223-253 | Last validation error wins; coverage INSERT and TDQ write results are ignored | **Preserve** message precedence; **Fix** ignored write errors | PO | AB-525 | Proposed — pending PO sign-off |
| [Q-30](#q-30) | POLQRY L32-38, L107-160 | Customer and coverage rows are read but never displayed; max 20 coverages; "API flag check" not implemented | **Preserve** facade contract (inquiry API returns all coverages) | PO | AB-524 | Proposed — pending PO sign-off |
| [Q-31](#q-31) | `jcl/DAILY-EXTRACT.jcl` L11, L18-20, L41-44, L86-94 | Plain FTP, no encryption step despite the 2012 comment; customer extract listed but never produced; DSNTEP2 writes SYSPRINT, not the POLOUT/COVOUT DDs | **Fix** transport; **Preserve** record layout once captured | CLM + SEC | AB-527 | Proposed — pending PO sign-off |
| [Q-32](#q-32) | `jcl/DAILY-EXTRACT.jcl` L36-39, L47-55, L75-80 | Policy extract window is `LAST_UPDATED >= CURRENT DATE - 1 DAY` (overlapping); coverage extract is a full snapshot; same-day rerun fails on DISP=NEW | **Preserve** selection; **Fix** rerun | CLM + OPS | AB-527 | Proposed — pending PO sign-off |
| [Q-33](#q-33) | `jcl/MONTHLY-EXPOSURE.jcl` L30-33, L40-57, L63-70 | `POLICY_COUNT` counts coverage rows; premium sums COVERAGES.PREMIUM (never maintained); remote file name overwritten monthly; plain FTP | **Preserve** aggregates; **Fix** transport | ACT + SEC | AB-527 | Proposed — pending PO sign-off |
| [Q-34](#q-34) | `java-facade` PolicyController L27-28, application.properties L14, L33 | Facade has no authentication; actuator endpoints unsecured | **Fix** | SEC | AB-524 | Proposed — pending PO sign-off |
| [Q-35](#q-35) | PREMBAT `3000-PROCESS-POLICIES` L129-130, `3100-FETCH-POLICY` | `PERFORM 3100-FETCH-POLICY UNTIL SQLCODE NOT = 0` tests the INSERT's SQLCODE: the first failed PREMIUMS INSERT ends the run, the rest of the book is not processed | **Fix** (count the error and continue) | OPS + ACT | AB-523 | Proposed — pending PO sign-off |

## Details

### Q-01

**POLRNW leap-year renewal expiry.**

- **Where:** `cobol/programs/POLRNW.cbl` `6000-CREATE-RENEWAL-TERM` L143-150 (`ADD 10000 TO POLICY-EXPIRY-DATE GIVING WS-NEW-EXPIRY-DATE`). The source comment at L147 admits it.
- **Behaviour:** The new expiry is the YYYYMMDD number plus 10000. A term expiring 20240229 renews to **20250229**, which is not a date. The value is stored in `POLICY-RECORD`, sent on the POLRCONF map and passed to the DB2 UPDATE of `POLICIES.EXPIRY_DATE` and `COVERAGES.EXPIRY_DATE` (L154-180).
- **Evidence:** golden `polrnw/POLRNW-LEAP` → `policy_expiry_date=20250229`; `POLRNW-L228` and `POLRNW-L231` show non-leap dates are fine. README "Known Limitations" #3.
- **Impact:** An invalid date is either rejected by DB2 (renewal fails with "DB2 ERROR UPDATING RENEWAL", see Q-09) or, depending on host-variable conversion, stored and then breaks downstream date maths (POLEND, Q-13).
- **Decision:** **Fix.** The Java renewal uses calendar arithmetic (`LocalDate.plusYears(1)`, so 2024-02-29 → 2025-02-28). Needs a `parity/known-differences.json` entry for `POLRNW-LEAP` / `policy_expiry_date` with `decision` = `docs/modernization/quirk-register.md#q-01`.
- **Owner:** PO. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-02

**POLRNW open-claims check is never enforced.**

- **Where:** `POLRNW.cbl` `3000-CHECK-RENEWAL-ELIGIBILITY` L111-117 selects `COUNT(*)` from `ACMEINS.CLAIMS` with `CLAIM_STATUS = 'OPEN'` into `WS-SQLCODE` (a misnamed working field, not the SQLCA). L118-119: "Business wants to block renewal if >3 open claims but this was never fully implemented - JH 2005". The SQLCODE of that SELECT is not checked either.
- **Behaviour:** Any number of open claims renews. If `ACMEINS.CLAIMS` is unavailable the negative SQLCODE is ignored and the renewal proceeds.
- **Evidence:** golden `polrnw/POLRNW-CLAIMS` (5 open claims) → renewed, `POLRCONF`.
- **Impact:** Policies with heavy open-claim activity renew automatically, contrary to the stated business rule.
- **Decision:** **Fix.** Block renewal when open claims > 3 (threshold from the 2005 comment; PO to confirm), with a clear error. The read of claims goes through the agreed Claims interface (ACMEINS.CLAIMS is owned by the Claims team, see [integrations](integrations.md#i-08)). Needs a known-differences entry for `POLRNW-CLAIMS` pointing at `#q-02`.
- **Owner:** PO + CLM. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-03

**PREMBAT territory table opened but never loaded; all factors are 1.00.**

- **Where:** `PREMBAT.cbl` `2000-LOAD-RATING-TABLES` L102-115: `DECLARE TERR_CURSOR`, `OPEN`, then immediately `CLOSE` ("simplified - production loads all rows", L113). `WS-TERRITORY-TABLE` / `WS-CLASS-TABLE` (L50-59) are never filled or read. `3200-CALCULATE-PREMIUM` L166-176 multiplies by literal 1.00 for territory, class and experience mod. `3300` L214-215 inserts literal `1.0000` into `TERRITORY_FACTOR`, `CLASS_FACTOR`, `EXPERIENCE_MOD`, `SCHEDULE_MOD`.
- **Evidence:** every `prembat` golden row has `territory_premium = class_premium = mod_premium = base_rate`.
- **Impact:** `ACMEINS.TERRITORY_FACTORS` content has no effect on premiums. Whether "production loads all rows" (a different load module) is an open question ([OQ-03](open-questions.md#oq-03)).
- **Decision:** **Preserve** at parity (factors effectively 1.00; `pas-rating` `PremiumCalculator` with the `PARITY` factor set matches). Turning on real territory/class factors is a separate, flag-guarded follow-up that stays **off by default** ([OQ-04](open-questions.md#oq-04)).
- **Owner:** ACT. **Target:** AB-522 (rating), AB-523 (batch). **Status:** Proposed — pending PO sign-off.

### Q-04

**Flat 15% renewal rate cap.**

- **Where:** `POLRNW.cbl` L29-30 (`WS-RATE-INCREASE-CAP VALUE 15.00`), `5000-APPLY-RATE-CAP` L133-141 ("varies by state ... Using flat 15% cap for now").
- **Behaviour:** One cap for every state. Because Q-05 fixes the uplift at 5%, `WS-RATE-CHANGE-PCT` never exceeds 15 and the cap branch (L137-138) is currently unreachable.
- **Evidence:** all renewing `polrnw` goldens show `rate_change_pct` ≤ 5.00. README "Known Limitations" #4.
- **Impact:** Regulatory exposure if state caps lower than 15% apply; latent today because the uplift is 5%.
- **Decision:** **Preserve** (port the flat cap and the dead branch faithfully; state-specific caps are a product/regulatory change outside the epic, see [OQ-05](open-questions.md#oq-05)).
- **Owner:** PO + ACT. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-05

**Hardcoded 5% renewal uplift.**

- **Where:** `POLRNW.cbl` `4000-CALCULATE-NEW-PREMIUM` L122-131 ("hardcoded - should be table-driven but actuarial never provided the table").
- **Behaviour:** New premium = old total premium x 1.05 regardless of product, state, claims or PREMBAT's calculation. POLRNW does not write `PREMIUMS`.
- **Evidence:** goldens `POLRNW-AC` (1000.00 → 1050.00), `POLRNW-EX`.
- **Decision:** **Preserve** (rating changes for pricing reasons are out of scope).
- **Owner:** ACT. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-06

**POLRNW truncates instead of rounding.**

- **Where:** `POLRNW.cbl` L126-130 and L137-138: `COMPUTE` without `ROUNDED` into `PIC S9(09)V99` / `S9(03)V99`.
- **Behaviour:** 1234.57 x 1.05 = 1296.2985 → **1296.29**; rate change 4.9996% → **4.99**.
- **Evidence:** goldens `POLRNW-TRUNC`, `POLRNW-CENT`.
- **Decision:** **Preserve.** Java must use `BigDecimal` with `RoundingMode.DOWN` at the PIC scale (not `pas-common` `Money`, which is HALF_UP).
- **Owner:** ACT. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-07

**POLRNW numeric overflows.**

- **Where:** `POLRNW.cbl` L144 (`ADD 1 TO POLICY-RENEWAL-COUNT`, PIC 9(3)) and L148-150 (`ADD 10000 ... GIVING WS-NEW-EXPIRY-DATE`, PIC 9(8)). No `ON SIZE ERROR`.
- **Behaviour:** renewal count 999 → **0**; expiry 99991231 → **1231** (00001231).
- **Evidence:** goldens `POLRNW-RC999`, `POLRNW-D9999`.
- **Impact:** Edge cases only (no real policy has 999 renewals; 9999-12-31 is a sentinel some feeds use for "open-ended").
- **Decision:** **Preserve** at parity; the Java model must accept the same values. Whether 99991231 sentinels exist in DB2 is [OQ-09](open-questions.md#oq-09).
- **Owner:** PO. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-08

**POLRNW zero-premium divide.**

- **Where:** `POLRNW.cbl` L128-130 divides by `WS-OLD-PREMIUM` with no zero guard and no `ON SIZE ERROR`.
- **Behaviour:** No abend; `WS-RATE-CHANGE-PCT` = **0.00** and the renewal proceeds with premium 0.00.
- **Evidence:** golden `POLRNW-ZEROP`. Behaviour on Enterprise COBOL for a divide-by-zero without SIZE ERROR should be confirmed by the mainframe team ([OQ-10](open-questions.md#oq-10)).
- **Decision:** **Preserve** (Java guards the divide and yields 0.00).
- **Owner:** PO. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-09

**POLRNW failed UPDATE still confirms.**

- **Where:** `POLRNW.cbl` `6000-CREATE-RENEWAL-TERM` L166-169 sends the error text, but `0000-MAIN-LOGIC` L55-60 then still performs `7000-UPDATE-COVERAGES` and `8000-SEND-CONFIRMATION`.
- **Behaviour:** Operator receives "DB2 ERROR UPDATING RENEWAL" **and** the POLRCONF confirmation; COVERAGES dates are moved to the new term even though POLICIES was not.
- **Evidence:** golden `POLRNW-UPD911` → `screens_sent=TEXT+POLRCONF`.
- **Impact:** POLICIES and COVERAGES disagree on term dates after a failed renewal (no explicit SYNCPOINT ROLLBACK; the CICS task ends normally and commits).
- **Decision:** **Fix.** Java renewal runs in one transaction and returns an error with no partial update. Known-differences entry for `POLRNW-UPD911` / `screens_sent` pointing at `#q-09`.
- **Owner:** PO + MF. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-10

**POLEND never sets the endorsement type.**

- **Where:** `POLEND.cbl` L33-38 declares `WS-ENDORSEMENT-TYPE` (CAD/CRM/LCH/ACH/CAN) with no VALUE; nothing moves data into it — `2000-RECEIVE-ENDORSEMENT` (L75-101) receives `POLEMAP` into `POLICY-RECORD` only. `3000-VALIDATE-ENDORSEMENT` L108-111 rejects blanks; L148 inserts it into `ENDORSEMENTS.ENDORSEMENT_TYPE`.
- **Behaviour:** The value is whatever is in task storage. With spaces every endorsement fails "ENDORSEMENT TYPE IS REQUIRED"; with garbage it is stored as the type.
- **Evidence:** golden `polend/POLEND-BLANK`; the harness `ws_endorsement_type` fixture input simulates initial storage (parity/README "GnuCOBOL vs z/OS caveats").
- **Decision:** **Preserve** at parity (the Java port treats the type as an input that defaults to blank). How production endorsements actually get a type is [OQ-06](open-questions.md#oq-06).
- **Owner:** PO. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-11

**POLEND never computes the premium adjustment.**

- **Where:** `POLEND.cbl` L32 declares `WS-PREMIUM-ADJUST`; no statement assigns it. `4000-CALCULATE-PRORATA` (L114-129) computes `WS-PRORATA-FACTOR` which is then never used. L158-166 adds `WS-PREMIUM-ADJUST` to `POLICY-TOTAL-PREMIUM` and updates POLICIES; L149 inserts it into `ENDORSEMENTS.PREMIUM_ADJUSTMENT`.
- **Behaviour:** Every endorsement records adjustment **0.00** and leaves the premium unchanged (on z/OS the uninitialised COMP-3 may also contain invalid packed data → possible S0C7).
- **Evidence:** every `polend` golden has `premium_adjust=0.00` and unchanged `total_premium`.
- **Decision:** **Preserve** at parity. Implementing pro-rata premium is a product change ([OQ-06](open-questions.md#oq-06)).
- **Owner:** PO. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-12

**POLEND day counts truncate.**

- **Where:** `POLEND.cbl` L29-30 (`WS-DAYS-REMAINING`, `WS-DAYS-IN-TERM` PIC 9(03), unsigned), used at L117-125.
- **Behaviour:** A 1096-day term is stored as **96** (factor 3.802083); a policy already past expiry (-14 days) shows **+14**.
- **Evidence:** goldens `POLEND-BIGTERM`, `POLEND-DPAST`, `POLEND-DEND`, `POLEND-ZEROT`.
- **Impact:** None today because the factor is unused (Q-11); matters if pro-rata is ever implemented.
- **Decision:** **Preserve** at parity.
- **Owner:** PO. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-13

**POLEND on invalid dates.**

- **Where:** `POLEND.cbl` L117-122 `FUNCTION INTEGER-OF-DATE` on `POLICY-EXPIRY-DATE`.
- **Behaviour:** An invalid date such as 20250229 (produced by Q-01) makes `INTEGER-OF-DATE` return 0 under GnuCOBOL → term days 558, factor 1.164874. Enterprise COBOL behaviour for an out-of-range argument may differ (zero result and/or a runtime message).
- **Evidence:** golden `POLEND-INVDT`.
- **Decision:** **Preserve** the GnuCOBOL-pinned result for parity, pending mainframe confirmation of z/OS behaviour ([OQ-10](open-questions.md#oq-10)). Once Q-01 is fixed no new invalid dates are produced; data migration (AB-529) must report any existing ones.
- **Owner:** MF. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-14

**POLEND continues after a failed INSERT.**

- **Where:** `POLEND.cbl` L152-155 sets `WS-ERROR-MSG` when the ENDORSEMENTS INSERT fails but `5000-APPLY-ENDORSEMENT` continues to the POLICIES UPDATE (L158-166), and `0000-MAIN-LOGIC` (L55-60) has already committed to running `6000-WRITE-AUDIT-TRAIL` and `7000-SEND-CONFIRMATION`. Also, a RECEIVE failure (L82-85) does not skip the SELECT.
- **Behaviour:** No endorsement row, but the policy is updated, the audit TSQ is written and **POLECONF** is shown.
- **Evidence:** golden `POLEND-INSF` → error text with `screens_sent=POLECONF`.
- **Decision:** **Fix.** Java endorsement is atomic and returns the error. Known-differences entry for `POLEND-INSF` / `screens_sent` pointing at `#q-14`.
- **Owner:** PO + MF. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-15

**POLEND endorsement types are not applied.**

- **Where:** whole of `POLEND.cbl`. The header (L12-17) promises coverage additions/removals, limit, address and named-insured changes, and the 2007 note promises mid-term cancellation. The program only writes an ENDORSEMENTS row and adds a zero adjustment to the premium. `CAN` never sets `POLICY_STATUS='CN'`; COVERAGES and POLICY_HOLDERS are never touched; `ENDORSEMENTS.DESCRIPTION` is never written; `EFFECTIVE_DATE` is the processing date (L148), not a requested effective date.
- **Decision:** **Preserve** at parity; implementing these behaviours is new product scope ([OQ-06](open-questions.md#oq-06)).
- **Owner:** PO. **Target:** AB-526. **Status:** Proposed — pending PO sign-off.

### Q-16

**UNDWRT unsigned arithmetic.**

- **Where:** `UNDWRT.cbl` L33 (`WS-RISK-SCORE PIC 9(04)`, unsigned), L121-134.
- **Behaviour:** Preferred tier subtracts 100 from a score of 25 → **75** (the sign is dropped), so a preferred high-credit customer scores higher than intended. A credit score above 900 makes `(900 - credit)/2` negative and it is stored as its absolute value (950 → 25; verified with GnuCOBOL 3.1.2 `-std=ibm`). Division truncates.
- **Evidence:** golden `undwrt/UNDWRT-PREF`.
- **Decision:** **Preserve** (UNDWRT arithmetic quirks port at parity).
- **Owner:** UW. **Target:** AB-525. **Status:** Proposed — pending PO sign-off.

### Q-17

**UNDWRT risk score mod 1000 on the policy record.**

- **Where:** `UNDWRT.cbl` L209 `MOVE WS-RISK-SCORE TO POLICY-RISK-SCORE`; `cobol/copybooks/POLICY-RECORD.cpy` L40 `POLICY-RISK-SCORE PIC 9(03)`.
- **Behaviour:** A score of 1150 becomes **150** in `POLICY-RECORD`, which is what goes on the `ACME.PAS.UNDERWRITING.DECISION` queue (L236-243) and the UWRTRES map. The DB2 UPDATE (L226-233) and decision INSERT use `WS-RISK-SCORE`, so DB2 holds 1150.
- **Evidence:** golden `UNDWRT-SCORE1000` → `risk_score=1150`, `policy_risk_score=150`.
- **Decision:** **Preserve** in the published message layout (consumers may depend on 3 digits; AB-528). Java stores the full score.
- **Owner:** UW. **Target:** AB-525, AB-528. **Status:** Proposed — pending PO sign-off.

### Q-18

**UNDWRT loss-ratio overflow.**

- **Where:** `UNDWRT.cbl` L40 (`WS-LOSS-RATIO PIC S9(03)V99`), L152-159.
- **Behaviour:** Loss ratios of 1000% or more are truncated to their low-order digits (200000% → **0.00**), so the worst loss histories skip the +200 penalty.
- **Evidence:** golden `UNDWRT-LRBIG`; boundaries `UNDWRT-LR80`, `UNDWRT-LR8001`.
- **Decision:** **Preserve** at parity; flagged to UW as a candidate post-cutover fix ([OQ-11](open-questions.md#oq-11)).
- **Owner:** UW. **Target:** AB-525. **Status:** Proposed — pending PO sign-off.

### Q-19

**UNDWRT accumulation reason lost.**

- **Where:** `UNDWRT.cbl` L181-186 builds "ACCUMULATION LIMIT EXCEEDED FOR BRANCH ..." in `WS-DECISION-REASON`; `5000-RENDER-DECISION` L190-208 unconditionally overwrites it.
- **Evidence:** golden `UNDWRT-ACCUM` → generic "REFERRED TO UW MANAGER".
- **Decision:** **Preserve** the stored `DECISION_REASON` text; Java may log the accumulation detail separately without changing stored values.
- **Owner:** UW. **Target:** AB-525. **Status:** Proposed — pending PO sign-off.

### Q-20

**UNDWRT same-day decision collision.**

- **Where:** `UNDWRT.cbl` `6000-WRITE-DECISION` L214-224 inserts into `UNDERWRITING_DECISIONS` with no SQLCODE check; L226-233 updates POLICIES anyway. `sql/ddl/create-tables.sql` L140-141: PK `(POLICY_NUMBER, DECISION_DATE)` where `DECISION_DATE` is a DATE.
- **Behaviour:** A second decision on the same policy on the same day fails with -803, is ignored, and the POLICIES row is updated with the new decision — the audit trail loses the second decision.
- **Evidence:** no golden (the harness stubs the INSERT as success); derived from DDL + code.
- **Decision:** **Fix.** The target audit table must keep every decision (e.g. surrogate key or timestamp in the key); Java must not drop audit rows. Data model change in AB-521, behaviour in AB-525.
- **Owner:** UW + DBA. **Target:** AB-521, AB-525. **Status:** Proposed — pending PO sign-off.

### Q-21

**UNDWRT hardcoded rules and decision codes.**

- **Where:** `UNDWRT.cbl` L18-22 / L190-208 (score bands 300/600/800), L162-167 (5M / 10M limit surcharges), L144-149 (claims > 3, > 5), L181 (hardcoded $500M branch accumulation, which also includes the policy being underwritten if already active), L34-38 (writes `AP`/`RS`/`RM`/`DC`). `POLICY-RECORD.cpy` L36-39 only defines `AP`/`PN`/`DC`/`RF` — `RS`/`RM` have no 88-level. `UNDERWRITER_ID` is always `'SYSTEM'`; `OVERRIDE_REASON` / `OVERRIDE_BY` are never written.
- **Evidence:** goldens `UNDWRT-299/300/599/600/799/800` (600 → RM, 800 → DC), `UNDWRT-LIM*`, `UNDWRT-CLAIMS*`.
- **Decision:** **Preserve** values and codes. Making thresholds configurable is a follow-up ([OQ-11](open-questions.md#oq-11)).
- **Owner:** UW. **Target:** AB-525. **Status:** Proposed — pending PO sign-off.

### Q-22

**PREMIUMS primary-key collision on rerun.**

- **Where:** `PREMBAT.cbl` `3300-WRITE-PREMIUM-RECORD` L192 (`COVERAGE_SEQ` always 1), L202-219 plain INSERT, L220-224 counts failures; `sql/ddl/create-tables.sql` L103-104 PK `(POLICY_NUMBER, COVERAGE_SEQ, TERM_EFFECTIVE_DATE)`.
- **Behaviour:** The key does not include `CALC_DATE`, so any second run for a policy in the same term — a same-day rerun after a failure, and also the next scheduled daily run — fails with -803 for every policy. Failures are only counted (RC 4, Q-26), never replaced.
- **Impact:** PREMIUMS keeps the first calculation of each term; reruns are not safe. Whether something outside this repo purges PREMIUMS before the run is [OQ-07](open-questions.md#oq-07).
- **Evidence:** golden `parity/programs/prembat-rerun` (AB-523): the INSERT stub answers -803 and PREMBAT ends RC 4 with the row not written. AB-523 also found that the run does not continue to the next policy after the first -803 — see [Q-35](#q-35); "-803 for every policy" above is therefore really "-803 for the first policy, then the run stops".
- **Decision:** **Fix.** AB-523 makes the premium job idempotent: a rerun **replaces** the row for the same key. Epic success criterion "reruns are safe".
- **Owner:** OPS + ACT. **Target:** AB-523. **Status:** Proposed — pending PO sign-off.

### Q-23

**PREMBAT rating simplifications.**

- **Where:** `PREMBAT.cbl` L151-164 base premium by policy type only (AUT 850, HOM 1200, COM 5000, LIF 400, HLT 3500, other 1000) — the fetched `TOTAL_PREMIUM`, `DEDUCTIBLE`, `COVERAGE_LIMIT` (L136-140) are ignored; L70-72 / L178-180 flat 3.5% tax, truncated; L182-183 flat $25 surcharge; L192 one row per policy with `COVERAGE_SEQ = 1`; `INSTALLMENT_CODE='AN'`, `DISCOUNT_PCT=0.00` (L216-217). PREMBAT never updates `POLICIES.TOTAL_PREMIUM` or `COVERAGES.PREMIUM`.
- **Evidence:** all `prembat` goldens (e.g. `POL-AUT-1` 850 + 29.75 + 25 = 904.75; `POL-BLANK-1`, `POL-LOWER-1`, `POL-CGL-1` fall to the 1000.00 default).
- **Decision:** **Preserve** (rating moves at parity).
- **Owner:** ACT. **Target:** AB-522, AB-523. **Status:** Proposed — pending PO sign-off.

### Q-24

**PREMBAT report prints packed bytes.**

- **Where:** `PREMBAT.cbl` L227-231 `STRING POLICY-NUMBER ' PREMIUM: ' WS-FINAL-PREMIUM` where `WS-FINAL-PREMIUM` is COMP-3 (L68).
- **Behaviour:** The PREMRPT line contains raw packed-decimal bytes, unreadable in the report dataset.
- **Evidence:** parity/README quirks table (PREMBAT report row); goldens capture PREMIUMS values instead.
- **Decision:** **Fix.** The Java job's run report prints edited amounts. The report is an operator artefact, not a downstream contract (confirm with OPS).
- **Owner:** OPS. **Target:** AB-523. **Status:** Proposed — pending PO sign-off.

### Q-25

**PREMBAT loses RC 16.**

- **Where:** `PREMBAT.cbl` `1000-INITIALIZE` L88-92: on open failure it moves 16 to `WS-RETURN-CODE` and `STOP RUN` before L257 (`MOVE WS-RETURN-CODE TO RETURN-CODE`) ever runs.
- **Behaviour:** The step ends with the default return code (0) although nothing was processed.
- **Decision:** **Fix.** The Java job fails with a non-zero exit status and FAILED batch status.
- **Owner:** OPS. **Target:** AB-523. **Status:** Proposed — pending PO sign-off.

### Q-26

**PREMIUM-BATCH notification logic.**

- **Where:** `jcl/PREMIUM-BATCH.jcl` L42 STEP020 `IEFBR14` (does nothing; the comment says "send email"); L45-48 STEP030 `COND=(4,LT,STEP010)` runs whenever STEP010 RC ≤ 4 and sends "PREMIUM BATCH COMPLETED WITH WARNINGS" via TSO SEND to user PASADMIN.
- **Behaviour:** A clean run (RC 0) still reports "with warnings"; a hard failure (RC > 4) sends nothing.
- **Decision:** **Fix.** Java job emits status via metrics/alerts on failure and partial failure.
- **Owner:** OPS. **Target:** AB-523. **Status:** Proposed — pending PO sign-off.

### Q-27

**POLNEW policy-number truncation (new finding).**

- **Where:** `POLNEW.cbl` L29 `WS-POLICY-SEQ PIC 9(10)`; L138-145 `STRING 'POL' WS-POLICY-SEQ DELIMITED BY SIZE INTO POLICY-NUMBER`; `POLICY-RECORD.cpy` L12 `POLICY-NUMBER PIC X(12)`. DDL L184-190: `POLICY_SEQ` starts at 1000000.
- **Behaviour:** 'POL' + 10 digits is 13 characters; STRING has no `ON OVERFLOW` and silently drops the last digit. Sequence values 1000000-1000009 all produce `POL000100000`; 1000010 produces `POL000100001`. Only the first INSERT of each block of ten succeeds; the other nine fail with -803 → "DB2 ERROR INSERTING POLICY RECORD" and consume a sequence value.
- **Evidence:** reproduced with GnuCOBOL 3.1.2 `cobc -x -std=ibm` on the same declarations (scratch program, not committed). No golden yet (POLNEW is not in the harness, [OQ-01](open-questions.md#oq-01)).
- **Impact:** Policy numbers in DB2 are effectively `POL` + `floor(seq/10)` zero-padded to 9 digits; ~90% of new-business attempts fail. Must be confirmed against production data ([OQ-02](open-questions.md#oq-02)) — the deployed load module may differ from this source.
- **Decision:** **Fix** the collisions while **preserving the format** (`POL` + 9 digits, 12 chars) so existing numbers and downstream layouts stay valid; new numbers must not collide with migrated ones.
- **Owner:** PO + DBA. **Target:** AB-525. **Status:** Proposed — pending PO sign-off.

### Q-28

**POLNEW leaves dates and coverage amounts unset (new finding).**

- **Where:** `POLNEW.cbl` `5000-INSERT-POLICY` L148-163 moves most fields into `DCLPOLICIES` but never `POL-EFF-DT`, `POL-EXP-DT` or `POL-INCEPT-DT` (L47-48, L55; PIC X(10), whereas the map supplies 9(8) YYYYMMDD). They are inserted at L175-179 and reused for COVERAGES at L235-236. `6100-WRITE-COVERAGE` never sets `COV-LIMIT`, `COV-DEDUCTIBLE`, `COV-PREMIUM`, and `COV-SEQUENCE-NUM` (L224) has no initial value. `6000-INSERT-COVERAGES` L194-220 creates nothing for LIF / HLT. The coverage INSERT result is not checked.
- **Behaviour (as written):** Dates are uninitialised storage, which DB2 would reject for DATE columns (-180/-181) or store as garbage; coverages carry zero/garbage amounts and an unpredictable sequence.
- **Impact:** This source cannot have produced the policies that exist in DB2 today; either the deployed module differs or there is a conversion layer (e.g. DCLGEN/precompiler options) not in the repo ([OQ-02](open-questions.md#oq-02), [missing artifacts](missing-artifacts.md#m-07)).
- **Decision:** **Fix**: Java new business takes effective/expiry dates from the request, sets inception = effective, and numbers coverages from 1. Coverage amounts follow whatever rule the PO confirms. Add POLNEW to the harness first to pin current behaviour.
- **Owner:** PO + MF. **Target:** AB-525. **Status:** Proposed — pending PO sign-off.

### Q-29

**POLNEW validation and unchecked writes.**

- **Where:** `POLNEW.cbl` `3000-VALIDATE-INPUT` L108-134: each failing check overwrites `WS-ERROR-MSG`, so the **last** failure is reported; the POLICY_HOLDERS lookup (L124-133) runs even when earlier checks fail. A RECEIVE failure (L103-105) does not stop validation. `6100-WRITE-COVERAGE` (L226-237) and `7000-SEND-MQ-MESSAGE` (L243-252, "Non-fatal: log error but continue" — nothing is logged) ignore errors.
- **Decision:** **Preserve** the single-message precedence (holder-not-found > limit > past date > type > holder-id) for the screen-equivalent error; **Fix** ignored write errors (coverage insert and underwriting-request publication must succeed or the whole request fails / is retried via outbox — AB-528).
- **Owner:** PO. **Target:** AB-525 (AB-528 for the message). **Status:** Proposed — pending PO sign-off.

### Q-30

**POLQRY reads more than it shows.**

- **Where:** `POLQRY.cbl` L32-38 (20-entry coverage table), `3000-READ-CUSTOMER` L107-119 (no SQLCODE check), `4000-READ-COVERAGES` L121-136 (stops at 20), `5000-DISPLAY-POLICY` L153-160 sends `POLQDET` **from `POLICY-RECORD` only** — customer and coverage data never reach the screen (unless the BMS map, not in repo, overlays storage). Header L10 claims an "API flag check for facade" that does not exist.
- **Decision:** **Preserve** the read-only semantics; the inquiry API (AB-524) follows the java-facade contract (`compat/api/snapshots/policies-v1.json`), which already returns all coverages. The 20-row cap is not carried over.
- **Owner:** PO. **Target:** AB-524. **Status:** Proposed — pending PO sign-off.

### Q-31

**DAILY-EXTRACT transport and content.**

- **Where:** `jcl/DAILY-EXTRACT.jcl` L11 ("2012-11-01 - Added encryption step") but no encryption step exists; L86-94 STEP030 runs the z/OS FTP client in clear text to `CLAIMSRV.ACME.LOCAL`. L18-20 list `ACME.PAS.EXTRACT.CUSTOMER.DAILY`, which no step creates. L41-44 / L69-72 run `DSNTEP2`, which writes its formatted result to `SYSPRINT`; nothing writes to the `POLOUT` (L36-39) or `COVOUT` (L65-68) DDs, so as written the transferred datasets would be empty.
- **Impact:** Regulatory finding (README "Known Limitations" #7). The real byte layout received by Claims is unknown from the repo ([OQ-08](open-questions.md#oq-08)).
- **Decision:** **Fix** transport (encrypted transfer, epic success criterion "no unencrypted file transfers"); **Preserve** record content/layout byte-for-byte once a production sample is captured and the Claims team signs off.
- **Owner:** CLM + SEC. **Target:** AB-527. **Status:** Proposed — pending PO sign-off.

### Q-32

**DAILY-EXTRACT selection window and rerun.**

- **Where:** `jcl/DAILY-EXTRACT.jcl` L47-55 policies `WHERE POLICY_STATUS IN ('AC','PN') AND LAST_UPDATED >= CURRENT DATE - 1 DAY` (from midnight yesterday, so consecutive runs overlap by ~23 h and a status change to CN/EX/LP is never sent); L75-80 coverages: all active rows, no date filter (full snapshot). L37 / L66 `DISP=(NEW,CATLG,DELETE)` with no prior delete step — a same-day rerun fails with a duplicate dataset name.
- **Decision:** **Preserve** the selection semantics (Claims depends on them); **Fix** rerun (idempotent overwrite of the day's file).
- **Owner:** CLM + OPS. **Target:** AB-527. **Status:** Proposed — pending PO sign-off.

### Q-33

**MONTHLY-EXPOSURE aggregates and transport.**

- **Where:** `jcl/MONTHLY-EXPOSURE.jcl` L40-57: `COUNT(*) AS POLICY_COUNT` counts **coverage** rows of the POLICIES x COVERAGES join; `SUM(C.PREMIUM)` sums `COVERAGES.PREMIUM`, which POLNEW never sets (Q-28) and PREMBAT never updates (Q-23); `RATING_TERRITORY` / `CLASS_CODE` are never written by any PAS program (groups are null). L30-33 output is a DSNTEP2 report, not CSV, despite the `.csv` remote name. L63-70 clear-text FTP to `ACTUARIAL.ACME.LOCAL`, always to `/actuarial/data/exposure_monthly.csv` (previous month overwritten).
- **Decision:** **Preserve** aggregate semantics and column meaning (actuarial models depend on them; renaming is a consumer decision); **Fix** transport and keep one dated file per month.
- **Owner:** ACT + SEC. **Target:** AB-527. **Status:** Proposed — pending PO sign-off.

### Q-34

**Facade without authentication.**

- **Where:** `java-facade/.../controller/PolicyController.java` L27-28 ("No authentication ... relies on network segmentation"); `application.properties` L33 sets `management.security.enabled` to false; L14 connects straight to DB2 `DBPD`. Existing tickets AB-364 / AB-365.
- **Decision:** **Fix.** Inquiry API (AB-524) is authenticated and authorised; actuator restricted. Consumers keep the v1 response shape (compat gate).
- **Owner:** SEC. **Target:** AB-524. **Status:** Proposed — pending PO sign-off.

### Q-35

**PREMBAT stops at the first failed PREMIUMS INSERT.**

- **Where:** `PREMBAT.cbl` `3000-PROCESS-POLICIES` (`PERFORM 3100-FETCH-POLICY UNTIL SQLCODE NOT = 0`) and `3100-FETCH-POLICY` / `3300-WRITE-PREMIUM-RECORD`.
- **Behaviour:** The loop condition is evaluated after 3100 returns, when SQLCODE holds the result of the INSERT, not of the FETCH. Any failed INSERT (-803 on a rerun, Q-22, or any other DB2 error) ends the loop: the failed policy is counted as an error, the remaining active policies are never fetched, the summary is written and the job ends RC 4 — which STEP030 reports as "completed with warnings" (Q-26).
- **Impact:** One bad row silently leaves the rest of the book without a premium for the day. On a same-term rerun the run does one policy and stops.
- **Evidence:** found by AB-523 while characterising Q-22: the harness fed three rerun fixtures (all -803) and PREMBAT emitted one PARITY line and ended RC 4. The committed `prembat-rerun` golden keeps one case because the harness expects one output line per fixture.
- **Decision:** **Fix.** `premiumRecalculationJob` counts a failed PREMIUMS write as an error and carries on with the next policy (Spring Batch skip, up to `pas.batch.premium.skip-limit` per partition, after which the run FAILS and alerts). With Q-22 fixed, the -803 trigger no longer occurs.
- **Owner:** OPS + ACT. **Target:** AB-523. **Status:** Proposed — pending PO sign-off.
