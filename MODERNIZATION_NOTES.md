# Modernization Slice: PREMBAT Premium Calculation

First slice of the PAS modernization: the premium calculation in the
`PREMBAT` batch program is now also implemented in Java, inside the existing
REST facade, with a parity harness that runs the same inputs through the
COBOL and the Java code and diffs every field.

| | |
|---|---|
| Legacy program | `cobol/programs/PREMBAT.cbl` (paragraph `3200-CALCULATE-PREMIUM`) |
| Java implementation | `java-facade/src/main/java/com/acme/insurance/pas/rating/PremiumCalculator.java` |
| Java level | Java 8 / Spring Boot 1.5, the facade's existing convention (see Dockerfile) |
| Parity harness | `modernization/prembat/run-parity.sh` (GnuCOBOL 3.x + Maven) |
| New read-only endpoint | `GET /api/v1/policies/{policyNumber}/premium-calculation` |

## Why PREMBAT

- **It's core business logic.** It produces every premium in `PREMIUMS`, and
  the facade serves those premiums through `POLICIES.TOTAL_PREMIUM` /
  `COVERAGES.PREMIUM`.
- **It stands alone.** It's a batch program with no CICS calls, no `CALL`ed
  subprograms and only three copybooks. All of its DB2 access sits in the
  cursor/insert paragraphs, so the calculation paragraph can run without a
  mainframe.
- **It's on the known-limitations list.** README items 2 (hardcoded rating
  factors), 4 (no state-specific rules) and 6 (a ~4 hour single-threaded batch)
  all point here.

The facade doesn't call COBOL directly: it reads DB2 tables that the COBOL
programs populate. PREMBAT is the program behind the premium figures the
facade returns.

## Program inventory and dependency map

```
CA-7 PAS0050 (daily 01:00)
  └─ jcl/PREMIUM-BATCH.jcl
       STEP010  IKJEFT01 → DSN RUN PROGRAM(PREMBAT) PLAN(PASBATCH)
       STEP020  IEFBR14  (RC check)
       STEP030  TSO SEND to PASADMIN if RC > 4
         └─ PREMBAT.cbl  (batch, no CICS)
              COPY POLICY-RECORD    (POLICY-TYPE 88-levels drive base rate)
              COPY COVERAGE-RECORD  (copied, not used by the calc)
              COPY PREMIUM-RECORD   (output layout → PREMIUMS)
              DB2 read : TERRITORY_FACTORS (cursor opened, never loaded)
              DB2 read : POLICIES WHERE POLICY_STATUS = 'AC'
              DB2 write: PREMIUMS (one row per policy, COVERAGE_SEQ = 1)
              File out : PREMRPT (132-byte report)

Downstream readers of PREMIUMS / premium columns
  java-facade  PolicyRepository → /api/v1/policies/{n}, /coverages
  jcl/MONTHLY-EXPOSURE.jcl (actuarial exposure extract)
  jcl/DAILY-EXTRACT.jcl    (claims / broker flat files)
```

Paragraph map: `0000-MAIN-LOGIC` → `1000-INITIALIZE` → `2000-LOAD-RATING-TABLES`
→ `3000-PROCESS-POLICIES` → `3100-FETCH-POLICY` → **`3200-CALCULATE-PREMIUM`** →
`3300-WRITE-PREMIUM-RECORD` → `4000-WRITE-SUMMARY` → `9999-TERMINATE`.
This slice replaces `3200` only. The I/O paragraphs stay on the mainframe.

## Business rules extracted

| # | Rule | COBOL source | Java |
|---|------|--------------|------|
| BR-1 | Base premium by policy type: AUT 850.00, HOM 1200.00, COM 5000.00, LIF 400.00, HLT 3500.00 | `EVALUATE TRUE` on `POL-TYPE-*` 88-levels | `BASE_PREMIUMS` map |
| BR-2 | Any other type gets 1000.00. Matching is exact and case-sensitive on the 3-char field, so `CGL`, `aut`, `AU `, and blanks all get the default rate | `WHEN OTHER` | `basePremiumFor` |
| BR-3 | Territory, class and experience factors are applied in that order. **All three are currently hardcoded to 1.00.** The `TERRITORY_FACTORS` cursor is opened and closed but never loaded | `COMPUTE ... * 1.00` ×3 | `RatingFactors.LEGACY` (pluggable) |
| BR-4 | Premium tax is a flat 3.50%, not state-specific | `WS-TAX-RATE VALUE 0.0350` | `TAX_RATE` |
| BR-5 | A flat 25.00 regulatory surcharge is charged per policy (added 2019-11-15) | `MOVE 25.00 TO WS-SURCHARGE` | `REGULATORY_SURCHARGE` |
| BR-6 | Final premium = modified premium + tax + surcharge | `COMPUTE WS-FINAL-PREMIUM` | `calculate` |
| BR-7 | **Every intermediate result is truncated, not rounded, to 2 dp.** Each COMPUTE stores into a `V99` COMP-3 field without `ROUNDED` | `PIC S9(09)V99 COMP-3` | `RoundingMode.DOWN` per step |
| BR-8 | There is no `ON SIZE ERROR`, so overflow silently drops high-order digits (premium > 999,999,999.99, or tax > 9,999,999.99) | pictures above | Java throws `PremiumOverflowException` (intentional divergence) |
| BR-9 | Only `POLICY_STATUS = 'AC'` policies are rated | `POL_CURSOR` | still on the mainframe (I/O) |

Findings worth raising with the business:
- The seed policy `POL-00000003` is type `CGL`, which isn't a known type, so
  it's rated at the 1000.00 default (final 1060.00).
- BR-3 means territory and class rating has never actually been applied in
  production. Any real factor table changes premiums.

## Parity harness

`modernization/prembat/run-parity.sh`:

1. **Extract.** awk copies `WS-CALC-FIELDS`/`WS-TAX-RATE` and paragraph `3200`
   verbatim out of `PREMBAT.cbl` into generated copybooks. The harness doesn't
   re-implement the COBOL, so if `PREMBAT.cbl` changes, the harness picks up
   the change.
2. **Compile.** `cobc -std=ibm` builds `harness/PREMPRTY.cbl`, a driver that
   swaps the DB2 cursor for a fixture file, with the real copybooks.
3. **Run COBOL** on 854 fixtures:
   - 14 **legacy** cases run the verbatim paragraph: every policy type,
     the seed policies, and malformed or unknown codes.
   - 840 **factor-sweep** cases run the same paragraph with its three
     `* 1.00` literals parameterised by sed (6 types × 7 territory × 4 class
     × 5 experience factors). These check that the Java truncation semantics
     still hold once real rating tables are introduced.
4. **Run Java.** `PrembatParityTest` feeds the same fixtures to
   `PremiumCalculator`. Its output must match `expected/cobol-golden.psv`
   (the checked-in COBOL output) line for line.
5. **Diff** the live COBOL output against the Java output, byte for byte.

Without GnuCOBOL, `mvn test` still checks the Java output against the golden
file.

### Parity results (GnuCOBOL 3.1.2, OpenJDK 8)

| Suite | Cases | Fields compared per case | Mismatches |
|-------|------:|------:|------:|
| Legacy (verbatim `3200`) | 14 | 7 | **0** |
| Factor sweep (parameterised `3200`) | 840 | 7 | **0** |
| **Total** | **854** | **5,978 values** | **0** |

Legacy outputs (identical in COBOL and Java):

| Policy type | Base | Tax (3.5%) | Surcharge | Final |
|---|---:|---:|---:|---:|
| AUT | 850.00 | 29.75 | 25.00 | 904.75 |
| HOM | 1200.00 | 42.00 | 25.00 | 1267.00 |
| COM | 5000.00 | 175.00 | 25.00 | 5200.00 |
| LIF | 400.00 | 14.00 | 25.00 | 439.00 |
| HLT | 3500.00 | 122.50 | 25.00 | 3647.50 |
| other (CGL, aut, blank, ...) | 1000.00 | 35.00 | 25.00 | 1060.00 |

**Negative control.** A Java port that rounds `HALF_UP` instead of truncating
fails parity on **673 of 840** factor-sweep cases. This was verified by
switching `RoundingMode.DOWN` to `HALF_UP`: the harness fails with 673
mismatches. The legacy cases alone can't catch this, because every hardcoded
base rate × 3.5% happens to be exact.

## Running it

```bash
# full parity run (needs cobc + JDK 8 + Maven)
modernization/prembat/run-parity.sh

# Java-only (golden file) + full facade test suite
cd java-facade && mvn test

# endpoint
cd java-facade && mvn spring-boot:run -Dspring.profiles.active=local
curl localhost:8080/api/v1/policies/POL-00000001/premium-calculation
```

## Next steps (not in this slice)

1. Load `TERRITORY_FACTORS` and class tables into `RatingFactors` (BR-3). The
   parity sweep already covers non-unit factors.
2. Make tax and the rate cap state-specific (README limitation 4).
3. Shadow-run: compare `PremiumCalculator` against the nightly `PREMIUMS` rows
   for the full book before PREMBAT is retired.
4. Move the facade to Java 17 / Spring Boot 3. `PremiumCalculator` has no
   framework dependencies beyond `@Component`.
