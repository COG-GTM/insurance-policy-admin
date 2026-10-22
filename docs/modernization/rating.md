# PREMBAT rating component

`pas-platform/pas-rating` ports PREMBAT's `3200-CALCULATE-PREMIUM` arithmetic into a plain Java
library. `PremiumCalculator` accepts a `RatingRequest`, resolves dated parameters and factors
through `RatingTableRepository`, and returns a `PremiumBreakdown`. The policy type is normalized
like its COBOL `PIC X(3)` input: null becomes blank, trailing spaces are removed, leading spaces
are retained, and case is not folded. Values longer than three characters are rejected.

## Calculation and storage

PREMBAT source locations below are relative to `cobol/programs/PREMBAT.cbl`.

| Step | PREMBAT source | Input and result | Stored picture |
|------|----------------|------------------|----------------|
| Base premium selection | 149–165 | Matched policy type, or `WHEN OTHER` | `WS-BASE-PREMIUM S9(9)V99` |
| Territory | 167–169 | Base × territory factor | `WS-TERR-PREMIUM S9(9)V99` |
| Class | 170–173 | Territory premium × class factor | `WS-CLASS-PREMIUM S9(9)V99` |
| Experience | 174–177 | Class premium × experience modifier | `WS-MOD-PREMIUM S9(9)V99` |
| Schedule (component step) | Factor literal in the record at 214 | Experience premium × schedule modifier; this step is not in COBOL | Rated premium `S9(9)V99` |
| Tax | 178–181 | Modified premium × tax rate | `WS-TAX-AMOUNT S9(7)V99` |
| Surcharge | 182–184 | Fixed surcharge | `WS-SURCHARGE S9(7)V99` |
| Total | 185–188 | Modified premium + tax + surcharge | `WS-FINAL-PREMIUM S9(9)V99` |
| Premium record base rate | 190–198 | Base premium stored at record precision | `PREM-BASE-RATE S9(7)V9999` |

Every numeric store truncates toward zero (`RoundingMode.DOWN`) at its COBOL scale. The scheduled
modifier is also stored in the component and applied as a calculation step; PREMBAT does not apply
it in its calculation, and the legacy value `1.0000` makes this step a no-op. It is retained for
future table-driven rating. Arithmetic that exceeds a PIC's integer capacity throws
`PremiumOverflowException` rather than wrapping or dropping high-order digits. Capacity is checked
after truncation. `premiumRecordBaseRate()` separately checks the `S9(7)V9999` record field.

## Rating tables and modes

Policy-service migration V4 contains `pas.rating_factor_sets`, `pas.territory_factors`, and
`pas.class_factors`; the LEGACY factor set is neutral, while the illustrative set exists for
controlled tests. Migration V10 adds `pas.rating_parameters` and seeds the PREMBAT base premiums,
experience/schedule modifiers, tax, and surcharge transcribed from `3200-CALCULATE-PREMIUM`
(Q-07). Exact policy-type parameters win over `*` independent of date; within that specificity,
the latest effective date not after the rating date wins. Territory and class factor resolution use
the same exact/wildcard and effective-date precedence as `RatingFactorRepository`.

| Setting | Behavior |
|---------|----------|
| `pas.rating.factor-mode` | Component setting, default `legacy` |
| `PAS_RATING_FACTOR_MODE=legacy` | Always reads factor set `LEGACY`, regardless of which set is active |
| `PAS_RATING_FACTOR_MODE=tables` | Reads the single active factor set; no active set or missing factor fails closed |

The default preserves Q-03: PREMBAT's territory cursor is opened but not fetched, and the factors
written to the premium record remain neutral. Enabling real factors is a separate flagged
follow-up: activate an Actuarial-filed set and set mode to `tables` only after a parallel run.
Changing the factor set or activating rated factors changes premiums and is not part of this
compatibility default (Q-03). The exact base rates and charges remain table values from Q-07.

The PREMBAT parity adapter mirrors the legacy driver loop, including stopping on the first failed
premium insert (Q-05); the batch job's corrected continuation behavior is tracked separately under
AB-523. Current PREMBAT fixtures compare to COBOL without known differences.
