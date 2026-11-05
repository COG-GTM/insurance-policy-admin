# pas-persistence

Repositories and Flyway migrations for the PAS schema (ADR 0001 D3). May depend on `pas-domain` only.

## Flyway conventions

- Locations: `src/main/resources/db/migration/postgresql/` (target PostgreSQL 16) and
  `src/main/resources/db/migration/db2/` (transitional DB2 objects, reviewed and applied by the DBA
  team through its change process). Applications use `classpath:db/migration/{vendor}`, so each
  database only sees its own scripts. Nothing is ever added under the legacy `sql/` tree.
- File names: `V<version>__<lower_snake_description>.sql`.
- Applied migrations are immutable; the compat gate (`compat/check_schema.py`) fails any edit.
- Each epic ticket uses only its own version range, in every location:

| Ticket | Versions | Ticket | Versions |
|---|---|---|---|
| AB-519 | V1-V9 | AB-525 | V50-V59 |
| AB-521 | V10-V19 | AB-526 | V60-V69 |
| AB-522 | V20-V29 | AB-527 | V70-V79 |
| AB-523 | V30-V39 | AB-528 | V80-V89 |
| AB-524 | V40-V49 | AB-529 | V90-V99 |
| | | AB-530 | V100-V109 |

`FlywayMigrationConventionsTest` checks the locations, names, ranges and that no version is used twice per
location.

| Version | Location | Ticket | What |
|---|---|---|---|
| V1 | postgresql | AB-519 | Creates the empty `acmeins` schema (tables arrive in AB-521) |
| V10 | postgresql | AB-521 | ACMEINS tables, keys, indexes and `POLICY_SEQ`, 1:1 with `sql/ddl/create-tables.sql` |
| V11 | postgresql | AB-521 | Positive-factor CHECK on `territory_factors`; new `pasapp.class_factors` |
| V11 | db2 | AB-521 | `PASAPP.CLASS_FACTORS` (DBA-applied) |
| V12 | postgresql | AB-521 | PII column classification and `acmeins.policy_holders_masked` |
| V13 | postgresql | AB-521 | `underwriting_decisions` keyed by `decision_id` (quirk Q-18) |
| V20 | postgresql, db2 | AB-522 | Rate reference tables `pasapp.base_rates` and `pasapp.rating_parameters` (effective-dated) |
| V21 | postgresql, db2 | AB-522 | Seeds the rates PREMBAT hardcodes today (Q-27); rate changes are new effective-dated rows |

In local dev and CI `pas-policy-service` applies the migrations at start-up. Shared environments run Flyway
as a separate migration job with the schema-owner account before the release starts, application pods run
with `SPRING_FLYWAY_ENABLED=false`, and DB2 scripts are applied by the DBA team. Details and the type mapping:
[docs/pas/data-model.md](../../docs/pas/data-model.md).
