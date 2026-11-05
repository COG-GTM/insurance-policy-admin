# Rating component (AB-522)

PREMBAT `3200-CALCULATE-PREMIUM` is ported to the framework-free module `pas/pas-rating`. The premium batch job (AB-523) and the online flows (AB-524..AB-526) use the same `PremiumRater` bean, wired by `RatingConfiguration` in `pas-batch` and `pas-policy-service`.

## Flow

| Step | COBOL | Java |
|---|---|---|
| Load rates for the run | `2000-LOAD-RATING-TABLES` (cursor opened, never fetched, Q-03) | `ReferenceRatingTables.tableFor(ratingDate)`: reads `pasapp.base_rates` and `pasapp.rating_parameters` per rating date and reuses the result for `pas.rating.cache-ttl` (default 5 minutes). Expired dates are dropped from the cache when another date is loaded, so it holds at most the dates rated within one TTL. A batch run (AB-523) rates through `PremiumRater.snapshot()`, taken once at the start of the run: it copies every base-rate, parameter, territory-factor and class-factor row, so a row inserted during the run never mixes into it, also in table-driven mode. The four tables are copied by one `UNION ALL` statement (`RatingRateJdbcRepository.history()`, CHAR padding kept), so a release that commits rates and factors together is seen whole or not at all. The `PremiumRater` bean itself is the live, TTL-cached rater for the policy service. On PostgreSQL every statement reads one snapshot. On DB2 the DBA must confirm that the batch connection uses an isolation level giving the same guarantee (RS/RR or currently-committed); this is pending. |
| Base premium | `EVALUATE POL-POLICY-TYPE` with literals | `RatingTable.baseRate(policyType)`: exact match on the stored 3-character code, else `OTHER_BASE_RATE` (Q-35) |
| Territory, class, experience | `* 1.00` | `RatingFactorSource` chosen by the flag below |
| Tax | `WS-MOD-PREMIUM * WS-TAX-RATE` (0.0350) | `TAX_RATE` parameter |
| Surcharge | `MOVE 25.00` | `SURCHARGE_AMT` parameter |
| Rounding | no `ROUNDED`: truncation into each V99 field (Q-06) | `RoundingMode.DOWN` to 2 decimals after every step |
| Field width | no `ON SIZE ERROR`: high-order digits lost | `RatingOverflowException` (Q-522-1) |

`PremiumBreakdown` holds every value for audit: base, the factors, `WS-TERR-PREMIUM`, `WS-CLASS-PREMIUM`, `WS-MOD-PREMIUM`, tax rate, tax, surcharge and total. `toPremium(...)` maps it to the PREMIUMS row PREMBAT writes: annual installment code `AN`, `INSTALLMENT_AMT` null, base rate at 4 decimal places.

## Reference data (Flyway V20, V21)

No rate is in the Java code; `NoHardcodedRatesTest` fails on any decimal literal or `new BigDecimal` in `pas-rating/src/main`.

| Table | Key | Value | Seeded (V21) |
|---|---|---|---|
| `pasapp.base_rates` | `policy_type`, `effective_date` | `base_rate DECIMAL(9,2)` | AUT 850.00, HOM 1200.00, COM 5000.00, LIF 400.00, HLT 3500.00 |
| `pasapp.rating_parameters` | `parameter_code`, `effective_date` | `parameter_value DECIMAL(11,4)` | `OTHER_BASE_RATE` 1000.00, `TAX_RATE` 0.0350, `SURCHARGE_AMT` 25.00 |

All rows use `effective_date` 0001-01-01. The row in force is the latest one with `effective_date <= rating date`. To change a rate, insert a new row with a later date; never update an existing row. If a parameter has no row in force, rating fails with `RatingDataException` instead of using a default. The DB2 versions (`db/migration/db2/V20`, `V21`) are applied by the DBA team. The lookup SQL uses a correlated `MAX(effective_date)`, which both PostgreSQL and DB2 run.

## Feature flag: table-driven factors (Q-03, FU-03)

`pas.rating.factor-source` (environment variable `PAS_RATING_FACTOR_SOURCE`):

| Value | Behaviour |
|---|---|
| `legacy-neutral` (default) | Every factor is 1.0000, as in PREMBAT. Cent-level parity with the golden files. |
| `table-driven` | Territory and class factors come from `acmeins.territory_factors` and `pasapp.class_factors` for the request's codes on the rating date. A missing code or row keeps 1.0000. Experience and schedule mods stay 1.0000 until their source tables exist. |

The factor tables are empty, so `table-driven` gives the same premiums as `legacy-neutral` until rows are loaded (`PrembatReferenceRatingTest`). Switching the flag is the epic's one intended premium change. The steps are:

1. Actuarial / Pricing supply the factor rows and the territory-code mapping (FU-03).
2. Load the rows in a test environment.
3. Run `PremiumImpactReport.compare(book, legacyRater, tableDrivenRater)` over the production book and send its CSV (legacy total, table-driven total, delta, factors per policy) for approval.
4. After sign-off, set `PAS_RATING_FACTOR_SOURCE=table-driven` and record the change as an approved known difference for Q-03.

## Verification

- `make parity`: `ParityTest` runs `PrembatRatingAdapter`, which rates through `PremiumRater` over a Flyway-migrated PostgreSQL (Testcontainers), against all 12 `prembat-rating` golden files. `PrembatReferenceRatingTest` also checks every PREMIUMS row in the `prembat-batch-control` golden files.
- `PremiumCalculatorBoundaryTest`: cent boundaries for truncation (e.g. 49.99995 → 49.99), zero premium, and the largest value that fits each COBOL field.
- `RatingRateRepositoryIT`: seeded values, effective dating and table constraints.
- `RatingConfigurationIT` (pas-batch): Spring wiring with the flag off and on.

## Open items and sign-offs

| Item | Owner | Status |
|---|---|---|
| Confirm the V21 rows are the filed production rates (Q-27) | Actuarial / Pricing | pending |
| Factor table contents and the territory-code source for a policy (holder state, ZIP or agent territory?) (FU-03) | Actuarial / Pricing | pending |
| Approve the premium impact report before setting `table-driven` (Q-03) | Actuarial / Pricing + Product Owner | pending |
| Fail instead of silently truncating on overflow (Q-522-1) | Actuarial / Pricing | pending |
| Review DB2 `PASAPP.BASE_RATES` / `RATING_PARAMETERS` DDL, tablespace and grants | DBA team | pending |
| DS-07 security and data-governance review | Security + Data governance | pending |
| State-specific tax rates (FU-04) | Compliance + Actuarial | post-epic |
