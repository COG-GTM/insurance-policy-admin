# PAS COBOL parity harness (AB-520)

Runs the **real** z/OS COBOL programs (PREMBAT, POLRNW, POLEND, UNDWRT, POLNEW) under
GnuCOBOL 3.1.2 with every `EXEC SQL` / `EXEC CICS` block replaced by a
fixture-fed stub, captures program outputs as committed `golden.csv` files,
and checks the Java implementation (`pas-platform`) produces byte-identical
values for the same fixtures.

## How it works

1. `tools/cobol_harness.py` preprocesses `cobol/programs/<PROG>.cbl`:
   - `EXEC SQL INCLUDE SQLCA` → `COPY HXSQLCA` (harness SQLCA)
   - `COPY HXFIX` + `COPY HXWS` inserted at the end of WORKING-STORAGE
   - every procedure `EXEC SQL|CICS ... END-EXEC` block →
     `PERFORM STUB-<para-prefix>-<k>` (ordinal within the paragraph)
   - `DFHRESP(NORMAL)` → `0` (any other DFHRESP is a hard error)
   - `COPY HXSTUBS` (the program's `stubs.cpy`) + `COPY HXPROC` appended
2. The generated copybook `HXFIX` describes one stdin record: `HX-CASE-ID`
   PIC X(20) plus one field per spec input (signed numerics are
   `SIGN LEADING SEPARATE`, so all fixture data is plain text).
3. Compiled with
   `cobc -x -std=ibm -I build/<prog> -I cobol/copybooks -I parity/tools/cobol`.
   Under `-std=ibm` filename mapping is off, so PREMBAT's PREMRPT report file
   is literally `./PREMRPT` inside `build/<prog>`.
4. `per-case` mode: one process per fixture row (fresh WORKING-STORAGE, like a
   CICS task). `batch` mode (PREMBAT): one process fed all rows then an
   `*EOF*` sentinel.
5. Programs emit `PARITY|<case_id>|<out1>|...` lines (helpers in `HXPROC.cpy`);
   the harness splits them and compares to `golden.csv`. Separately, the
   stub name → original EXEC text mapping is committed as
   `parity/programs/<prog>/exec-blocks.txt` — a drift gate so a changed
   EXEC SQL/CICS block in the COBOL source can't go unnoticed: `check`
   diffs the regenerated manifest and fails with "EXEC SQL/CICS statements
   changed — review stubs.cpy/fixtures, then make parity-golden".

## Running

```sh
make parity-cobol    # unittest + regenerate + diff against committed goldens
make parity-golden   # regenerate and overwrite committed goldens
make parity-java     # Java adapters vs goldens (pas-platform, JDK 21)
make parity          # both
PARITY_REQUIRE_COBC=1 make parity-cobol   # fail instead of skip when cobc absent
```

Without `cobc`, `parity-cobol` prints a warning and exits 0 (so make targets
stay usable on machines without GnuCOBOL); CI sets `PARITY_REQUIRE_COBC=1`.

## Adding a program

1. `mkdir parity/programs/<prog>` (lowercase dir name).
2. Write `spec.json` (`program`, `source`, `mode`, `input` PICs, `output`
   column names), `fixtures.csv` (`case_id,note,<inputs>`), and `stubs.cpy`
   with exactly the `STUB-*` paragraphs the preprocessor generates — the
   harness reports missing/extra stubs by name.
3. `make parity-golden` then review + commit `golden.csv` and
   `exec-blocks.txt` (the EXEC-statement manifest used as a drift gate in
   step 1 above).

Stub conventions (see existing programs): the first-executed stub (the
ASKTIME replacement) does `PERFORM HX-READ-FIXTURE` + `PERFORM HX-BEGIN`;
FORMATTIME stubs move `HX-AS-OF-DATE` into the program's current-date field;
SELECT stubs move fixture values into the INTO host variables when the
fixture's `*_sqlcode` is 0 and always set SQLCODE; SEND map stubs append the
map name to `screens_sent`, SEND TEXT appends `TEXT`; side effects worth pinning (DB
inserts, TDQ writes) go into `HX-ITEMS` via `MOVE ... TO HX-ITEM` + `PERFORM HX-APPEND-ITEM`
(joined with `;`, see POLNEW's `writes`); the RETURN stub appends
every spec output, `PERFORM HX-EMIT`, `STOP RUN`.

## Known differences

`parity/known-differences.json` holds accepted COBOL↔Java deltas:
`{program, case_id, field, cobol, java, quirk, decision, rationale}`.
An entry applies only when the golden value equals `cobol` and the Java value
equals `java`; it must name a non-blank `quirk` and a `decision` that is
either an AB-517 quirk-register link
(`https://cog-gtm.atlassian.net/browse/AB-517...`) or a repo path under
`docs/modernization/`. Stale entries (unknown case/field or values that no
longer match) fail the run — they are deliberately brittle so the register
stays honest. Currently empty.

## GnuCOBOL vs z/OS caveats

- `per-case` runs get fresh WORKING-STORAGE each process; uninitialized
  numeric fields read as zero on GnuCOBOL (z/OS CICS gives unpredictable
  storage). POLEND's `ws_endorsement_type` input exists precisely to simulate
  initial storage content.
- COMP-3 fields are exercised through the same arithmetic as on z/OS, but
  DISPLAY-format edge cases (e.g. STRINGing a raw COMP-3 field, as the PREMBAT
  report line does) produce raw packed bytes that are not captured.
- `INTEGER-OF-DATE` on an invalid date returns 0 under GnuCOBOL (see quirks).
- `cobc -std=ibm` is required for filename/Reserved-word behaviour close to
  Enterprise COBOL; it is not a perfect emulation.

## Quirks surfaced by the goldens (feeds AB-517 quirk register)

| Program | Case | Quirk |
|---------|------|-------|
| PREMBAT | (report) | The PREMRPT report line STRINGs a raw COMP-3 field (`WS-FINAL-PREMIUM`) producing packed bytes; goldens intentionally capture the PREMIUMS values instead of report text. |
| POLRNW | POLRNW-LEAP | `ADD 10000 TO POLICY-EXPIRY-DATE` turns 20240229 into the invalid date **20250229** — the leap-year bug, faithfully stored and passed to DB2. |
| POLRNW | POLRNW-D9999 | Same +10000 on 99991231 overflows PIC 9(8): expiry becomes **1231** (00001231). |
| POLRNW | POLRNW-RC999 | `ADD 1` to renewal_count 999 wraps PIC 9(3) to **0**. |
| POLRNW | POLRNW-ZEROP | Rate-change COMPUTE divides by a zero premium; no abend, result is **0.00**. |
| POLRNW | POLRNW-CLAIMS | The open-claims COUNT lands in `WS-SQLCODE` (not the SQLCA) and is never checked — 5 open claims still renew. |
| POLRNW | POLRNW-UPD911 | After an UPDATE fails, the error text is sent **and** the POLRCONF confirmation screen is still sent (`TEXT+POLRCONF`). |
| POLRNW | POLRNW-TRUNC | COMPUTE without ROUNDED truncates: 1234.57 × 1.05 → **1296.29** and rate change 5.00% → **4.99**. |
| POLEND | (all) | `WS-ENDORSEMENT-TYPE` is never populated (RECEIVE fills only POLICY-RECORD), so under real CICS storage it is garbage/spaces and every endorsement risks "ENDORSEMENT TYPE IS REQUIRED". The `ws_endorsement_type` fixture simulates the initial bytes. |
| POLEND | (all) | `WS-PREMIUM-ADJUST` is computed nowhere — every golden shows premium_adjust **0.00** and total_premium unchanged. |
| POLEND | POLEND-BIGTERM | `WS-DAYS-IN-TERM` PIC 9(3) truncates a 1096-day term to **96**, inflating the pro-rata factor to 3.802083. |
| POLEND | POLEND-DPAST | `WS-DAYS-REMAINING` PIC 9(3) unsigned turns -14 days into **+14**. |
| POLEND | POLEND-INVDT | Invalid expiry 20250229 (as produced by POLRNW) makes `INTEGER-OF-DATE` return 0 → garbage term days (558) and factor 1.164874. |
| POLEND | POLEND-INSF | An INSERT failure sets the error message but the flow continues: audit written, **POLECONF confirmation still sent**. |
| UNDWRT | UNDWRT-PREF | `SUBTRACT 100` from score 25 on unsigned PIC 9(4) yields **+75** — a preferred, high-credit customer is scored safer than intended. |
| UNDWRT | UNDWRT-LRBIG | `WS-LOSS-RATIO` PIC S9(3)V99 truncates a 200000% loss ratio to **0.00**, skipping the +200 penalty entirely. |
| UNDWRT | UNDWRT-ACCUM | The accumulation-limit reason text is unconditionally overwritten by 5000-RENDER-DECISION — the detail never reaches the operator. |
| UNDWRT | UNDWRT-SCORE1000 | `POLICY-RISK-SCORE` PIC 9(3) truncates a real score of 1150 to **150** on the stored policy record. |
| UNDWRT | UNDWRT-600/800 | Exact-boundary scores behave as coded: 600 → RM (not RS), 800 → DC (not RM). |
| UNDWRT | UNDWRT-CREDIT999/CREDIT900P | (900 − 999) / 2 = −49 and 0 − 100 = −100 are stored unsigned: **49** and **100**. |
| UNDWRT | UNDWRT-LRDIV/LRDIV2 | Loss ratio is truncated, not rounded: 80.003 → 80.00 (no penalty), 80.333 → 80.33. |
| UNDWRT | UNDWRT-SCORE1000X | Score 1000 is DC but stores **0** on the policy record. |
| POLNEW | POLNEW-HOM | Sequence 1000001 formats to `POL000100000`, the same number as 1000000 (Q-27). |
| POLNEW | POLNEW-AUT.. | `POL-EFF-DT` / `POL-EXP-DT` / `POL-INCEPT-DT` are never set and coverage amounts stay 0 (Q-28). |
| POLNEW | POLNEW-MULTI* | Every check runs and the last failure wins; holder-not-found beats all (Q-29). |
| POLNEW | POLNEW-NEGLIM/XYZ | A negative limit and an unknown policy type are accepted (no coverages for XYZ). |
