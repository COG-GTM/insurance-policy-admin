# PAS data migration, reconciliation, parallel run and rollback (AB-529)

DB2 `ACMEINS` stays the system of record until cutover (ADR 0001). This ticket delivers the
tooling that copies it into PostgreSQL `acmeins`, keeps the copy in step while both systems run,
proves every day that the two agree, diffs the Java batch outputs against the mainframe for a full
billing cycle, and points the system back at DB2 if a cutover has to be undone. Nothing here runs
against production from this branch; the production steps are the gated runbook at the end.

| Piece | Where |
|---|---|
| Spring Batch jobs `migrationBulkLoad`, `migrationDeltaSync`, `migrationReconciliation`, `migrationReverseSync`, `migrationSystemOfRecord` | `pas/pas-batch/src/main/java/com/acme/insurance/pas/batch/migration/` |
| Results schema `acmeins_shadow` (Flyway V90, PostgreSQL) | `pas/pas-persistence/src/main/resources/db/migration/postgresql/V90__create_migration_shadow_schema.sql` |
| Schedules and alerts | `pas/deploy/migration/` |
| Parallel-run diff, triage and cycle gate | `tools/migration/parallel_run.py`, `docs/pas/parallel-run.json`, `docs/pas/parallel-run-triage.json` |
| Tests | `MigrationJobsIT` (two PostgreSQL 16 containers), `MigrationTableTest`, `RowCanonicalizerTest`, `tools/migration/test_parallel_run.py`; all run by `make migration` |

## Connections

The jobs open their own pools (they are not Spring `DataSource` beans, so the batch job repository
keeps using `PAS_DB_URL`):

| Property | Environment | Notes |
|---|---|---|
| `pas.migration.db2.url/username/password` | `PAS_MIGRATION_DB2_URL`, `PAS_MIGRATION_DB2_USERNAME`, `PAS_MIGRATION_DB2_PASSWORD` | DB2 for z/OS (`jdbc:db2://...:sslConnection=true;`). Read-only grant on ACMEINS before cutover. |
| `pas.migration.postgres.url/username/password` | `PAS_MIGRATION_PG_URL`, `PAS_MIGRATION_PG_USERNAME`, `PAS_MIGRATION_PG_PASSWORD` | PostgreSQL with `sslmode=verify-full`; owns writes to `acmeins` and `acmeins_shadow`. |
| `pas.migration.bucket-digits` | `PAS_MIGRATION_BUCKET_DIGITS` | Default 1 (10 buckets by the last key digit). Use 2 or 3 for the full book so a bucket fits in memory. |
| `pas.migration.batch-size` | `PAS_MIGRATION_BATCH_SIZE` | JDBC batch size, default 500. |
| `pas.migration.report-dir` | `PAS_MIGRATION_REPORT_DIR` | Daily `reconciliation-<date>.json/.md`; empty = reports only in `acmeins_shadow`. |
| `pas.migration.hash-tables` | | Default `POLICIES, COVERAGES, PREMIUMS` (the ticket's record-hash tables). |
| `pas.migration.sequence-gap` | | Default 100: how far above the other side's POLICY_SEQ the receiving sequence restarts. |

The vendor of each side is detected from the JDBC metadata (`DatabaseVendor`), so the same code
reads DB2 and PostgreSQL; the SQL used on `ACMEINS` is portable (`RIGHT`, `RTRIM`, `COALESCE`,
`SUM`, `COUNT`, `GROUP BY`).

## How rows are compared

All seven `ACMEINS` tables of the migration boundary are covered (`MigrationTable`); `CLAIMS` is
not (Claims-owned, FU-17). Each table is read **bucket by bucket**: rows whose business key ends in
the same digits are read from both sides, keyed by the business key and reduced to a SHA-256 hash of
the canonical row:

- `CHAR` values lose trailing blanks (DB2 pads `CHAR(n)` the same way PostgreSQL `char(n)` does,
  but drivers differ in whether the padding is returned), `DECIMAL` uses `toPlainString`, dates and
  timestamps ISO-8601 with microseconds, `NULL` a marker that cannot occur in data.
- `UNDERWRITING_DECISIONS` is matched on `(POLICY_NUMBER, DECISION_DATE)`, the DB2 primary key.
  The PostgreSQL surrogate `decision_id` (AB-521, V13) is never written by the migration, so its
  identity values stay PostgreSQL-owned.
- The bucket comparison is done in Java, so DB2 EBCDIC and PostgreSQL collation order never matter.

Writes go parent first (`POLICY_HOLDERS`, `TERRITORY_FACTORS`, `POLICIES`, `COVERAGES`, `PREMIUMS`,
`ENDORSEMENTS`, `UNDERWRITING_DECISIONS`) and deletes child first, in JDBC batches.

## Initial bulk load

`PAS_BATCH_JOB=migrationBulkLoad` refuses to start unless every PostgreSQL ACMEINS table is empty
(`resume=true` continues an interrupted load: rows already copied compare equal and are skipped).
It copies every table, restarts `acmeins.policy_seq` at the DB2 high-water mark plus the gap and
finishes with a full reconciliation. The job fails if the reconciliation finds a difference.

## Ongoing change sync

ADR 0001 D3 chooses log-based CDC (MSG-02) because `POLICIES.LAST_UPDATED` is the only change
timestamp, it is not set by every program, and deletes are invisible to a timestamp query. The
product is not licensed yet (FU-529-1), so `migrationDeltaSync` is the controlled delta mechanism
until then: it runs the same bucketed key-and-hash comparison as the bulk load and applies inserts,
updates **and deletes**, then reconciles. It needs no change timestamp. It is scheduled every 15
minutes outside the batch window (`pas/deploy/migration/cronjobs.yaml`); when CDC is live the
CronJob is suspended and the daily reconciliation stays.

The guard in `acmeins_shadow.system_of_record` makes forward sync refuse once PostgreSQL is the
system of record, so a forgotten CronJob cannot overwrite post-cutover writes with stale DB2 data.
Every sync step reads the guard again before each bucket it writes and stops if the system of
record changed during the run, and `migrationSystemOfRecord` refuses while a bulk load, delta sync
or reverse sync execution is running. Cutover therefore suspends the CronJob and lets the last run
finish (an execution left `STARTED` by a killed pod is marked abandoned first).

Strings are compared exactly; only the blank padding of fixed-width `CHAR` columns is removed when a
row is read, so a trailing-blank-only edit of a `VARCHAR` value is still synced.

## Daily reconciliation

`migrationReconciliation` (03:30 America/New_York, after the premium batch and the extracts) runs
and stores:

| Check | What |
|---|---|
| `COUNT` | Row count of every table (distinct `(POLICY_NUMBER, DECISION_DATE)` for decisions). |
| `TOTAL` | Row count and sums grouped by `POLICY_TYPE` and `BRANCH_CODE` of the policy: `POLICIES` `TOTAL_PREMIUM`, `COVERAGE_LIMIT`, `DEDUCTIBLE`; `COVERAGES` `PREMIUM`, `COVERAGE_LIMIT`, `DEDUCTIBLE`; `PREMIUMS` `TOTAL_PREMIUM`, `TAX_AMT`, `INSTALLMENT_AMT`. |
| `ORPHAN` | Policies whose holder does not exist (FU-16); must be equal on both sides. |
| `HASH` | Record-level hash comparison of `POLICIES`, `COVERAGES` and `PREMIUMS`: missing on either side, changed, duplicate key. |

Visibility for the team:

- `acmeins_shadow.recon_daily_summary` (one row per business date: status, failed checks, record
  differences), `recon_check` (every check with both values) and `recon_difference` (the keys of
  differing records, first 1,000 per run; the total is always counted). Row values and hashes are
  never stored, logged or reported, because `POLICY_HOLDERS` carries PII.
- `reconciliation-<date>.md` and `.json` in `PAS_MIGRATION_REPORT_DIR` for the daily stand-up.
- A run with differences or a failure exits non-zero, logs `pas.migration.alert` and pages
  (`PasReconciliationFailed`, `PasReconciliationMissing`, `PasDeltaSyncFailing`).

## Parallel run

For at least one full billing cycle the Java premium batch (`premiumRecalculation`) and extracts
(`claimsDailyExtract`, `actuarialMonthlyExposure`) run every business day against the PostgreSQL
copy, beside the mainframe. Each day both sets of outputs are captured under
`<captured>/<YYYY-MM-DD>/` and `<candidate>/<YYYY-MM-DD>/` with the file names in
`docs/pas/parallel-run.json` (`outputs`) and compared:

```
make migration-parallel-run CAPTURED=/secure/mainframe CANDIDATE=/secure/java REPORTS=/secure/reports
# = python3 tools/migration/parallel_run.py compare ... && python3 tools/migration/parallel_run.py gate ...
```

`compare` diffs record by record and writes `parallel-run-<date>.json/.md` with file, record
number, policy number and the classification of every difference, never the record content.
Every difference must match an entry in the triage register `docs/pas/parallel-run-triage.json`:

| Classification | Meaning | Required | Day clean? |
|---|---|---|---|
| `bug` | Java is wrong | `reference` (defect / follow-up) | Yes, also once `status` is `resolved`: the day only becomes clean when the Java outputs are regenerated, recaptured and the records no longer differ |
| `approved_fix` | Java deliberately differs | `quirk` whose decision in `quirks.json` is `fix` (e.g. Q-03 territory factors) | Yes |
| `data_issue` | Source data, not code (e.g. a DB2 row changed between the two runs, an orphan) | `reference` (data-fix ticket / runbook step) | Yes |
| (no match) | `unexplained` | | No |

Triage happens in a daily meeting (PAS engineering, mainframe team, product owner); entries are
added by pull request so the history is reviewed. `make migration` rejects catch-all entries, an
`approved_fix` without a `fix` quirk, and a `bug` or `data_issue` without a reference.

`gate` passes only when every business day of the agreed cycle (`cycle.start`..`cycle.end`, minus
weekends and `holidays`) has a clean report: zero unexplained differences and zero bug differences, and the cycle has at least
`cycle.min_business_days` business days. A candidate file that matches a tracked output pattern but
has no captured counterpart is an `extra_file` difference; the monthly exposure file is required on
the first business day of each month (`required_on`, convention to be confirmed under FU-529-2). Until the
cycle dates are agreed (FU-529-2) it fails.

## Rollback

Rollback is a reverse sync, not a restore, so writes made on PostgreSQL after cutover are kept:

1. Stop writes on PostgreSQL (scale the policy service and batch to zero).
2. `PAS_BATCH_JOB=migrationReverseSync confirmRollback=YES`: refuses unless PostgreSQL is the system
   of record; copies PostgreSQL ACMEINS into DB2 (inserts, updates, deletes), restarts DB2
   `ACMEINS.POLICY_SEQ` above the PostgreSQL high-water mark (no policy number is issued twice),
   reconciles, and only on a clean reconciliation sets the system of record back to DB2.
3. Point the CICS region and the batch schedule back at DB2.

Same-day underwriting decisions (Q-18): PostgreSQL keeps every decision (`decision_id`), DB2 keeps
one per `(POLICY_NUMBER, DECISION_DATE)`. If PostgreSQL holds several decisions for one policy and
day, the reverse sync refuses and reports how many pairs are affected. Export them from
`acmeins.underwriting_decisions` (they stay on PostgreSQL), then rerun with
`collapseSameDayDecisions=YES`, which sends the most recent decision of each day
(`CREATED_TIMESTAMP`) to DB2, the same single row per day that the legacy key allows.

The cutover decision itself is recorded with
`PAS_BATCH_JOB=migrationSystemOfRecord system=PG confirm=YES changedBy=<id> "reason=<change ticket>"`.
`MigrationJobsIT.rollbackRehearsalPointsBackAtTheLegacyDatabaseWithinTheWindow` rehearses the whole
sequence in CI (cutover, new policy and premium change on PostgreSQL, reverse sync, zero
differences, sequence above, DB2 back as system of record) and measures it. The rehearsal on DB2 for
z/OS with a production-sized book, and the agreed rollback window, are pending (FU-529-3) and are
recorded in `docs/pas/parallel-run.json` (`rollback.rehearsals`).

## Runbook (gated, never executed on this branch)

The steps RB-01..RB-06 are listed in `docs/pas/parallel-run.json` with `execute_on_branch: false`
and `dry_run_default: true`; `make migration` fails if either changes. Cutover (RB-05) needs, in
order: `gate` passed for one full cycle, reconciliation MATCH every day of it, a pre-production
rollback rehearsal inside the window, and every sign-off below approved.

## Sign-offs

| Party | Scope | Status |
|---|---|---|
| Mainframe team | Captured outputs per business day, PREMIUMS unload, read grant on ACMEINS, DB2 sequence restart for rollback | pending |
| DBA | Migration service accounts, load of the delta sync / CDC on DB2, pre-production rollback rehearsal | pending |
| Product owner | Billing cycle dates, triage decisions, "zero unexplained differences" sign-off | pending |
| Security / data governance | PII copied to PostgreSQL, retention of `acmeins_shadow` (DS-02) | pending |
| Claims and Actuarial | Parallel extract files | pending |
| ARB | DS-02 shadow schema, MSG-02 CDC product | pending |

## Decisions

- **Results schema on PostgreSQL only.** `acmeins_shadow` is a target-side store (ADR 0001 DS-02);
  nothing is created on DB2, and frozen `ACMEINS` stays untouched. V90 therefore exists only under
  `db/migration/postgresql`; no DB2 migration was needed in V90-V99.
- **Delta by comparison, not by timestamp,** until CDC (see above). Its cost is a full read of both
  sides per run; `bucket-digits` bounds memory, and the CDC product removes the cost (FU-529-1).
- **Parallel run on files, not on `acmeins_shadow` tables.** ADR 0001 places shadow batch outputs in
  `acmeins_shadow`; the premium job and the extracts already write files byte-identical to the
  mainframe (AB-523, AB-527), so the daily diff compares those files plus an ordered unload of
  PREMIUMS, and `acmeins_shadow` holds the reconciliation results. Running the premium job against a
  separate parallel PostgreSQL database keeps the CDC-fed copy clean.
