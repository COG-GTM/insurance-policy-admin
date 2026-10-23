# AB-529 DB2-to-PostgreSQL migration runbook

## Scope and safety

The migration batch process is inert unless `PAS_MIGRATION_ENABLED=true`. Keep source credentials
read-only and separate from the reverse-sync credentials. Delta and reconciliation schedules default
off; reverse sync defaults off. Use the dedicated `PAS_RECON_FINGERPRINT_KEY`, a base64-encoded
32-byte key distinct from both PII keys. Do not put passwords or key material in run logs, reports,
Jira comments, or parity extracts.

Migration writes intentionally do not publish policy outbox events (OQ-24). Reverse sync collapses
multiple same-day underwriting decisions to the latest `decision_id` to fit the DB2 key
`(policy_number, decision_date)` (OQ-25); obtain the decision owner's approval before cutover.

## Configure and run

Set `PAS_MIGRATION_SOURCE_URL`, `PAS_MIGRATION_SOURCE_USERNAME`, `PAS_MIGRATION_SOURCE_PASSWORD`,
`PAS_MIGRATION_SOURCE_SCHEMA`, `PAS_MIGRATION_SOURCE_SSL`, and the DB2 trust-store location when
required. The service creates an internal read-only DB2 pool. Set the encryption and blind-index
keys for the batch service before loading policyholder rows. For reverse sync, separately set
`PAS_MIGRATION_REVERSE_USERNAME` and `PAS_MIGRATION_REVERSE_PASSWORD`, then enable
`PAS_MIGRATION_REVERSE_SYNC_ENABLED`.

The supported jobs are:

| Job | Purpose |
|---|---|
| `bulkLoadJob` | Restartable initial load in FK order: holders, policies, then children |
| `deltaSyncJob` | Forward watermark-based source synchronization; paused runs exit `DISABLED` |
| `reverseSyncJob` | Reverse current rows for trigger-recorded changes in `pas.change_log`; `pas.batch.cli.drain` selects a bounded drain |
| `reconciliationJob` | Counts, grouped totals, canonical hashes, and safe JSON/Markdown reports |
| `parallelRunDiffJob` | Premium and legacy-extract comparison for one business date |

Run them from the batch container with `--pas.batch.cli.job=<job>`. Delta, reverse, and
reconciliation CLI runs use an epoch-millisecond `runId`; `parallelRunDiffJob` also accepts
`--pas.batch.cli.business-date=YYYY-MM-DD` and `--pas.batch.cli.cycle-id=<uuid>`. Reverse drain
defaults on and can be selected with `--pas.batch.cli.drain=true|false`. CLI exit codes are 3 for
`DISABLED`, 4 for `COMPLETED_WITH_ERRORS`, and 5 for `COMPLETED_WITH_DIFFERENCES`.

Watermarks are based on source change columns and a five-minute overlap by default. Watch
`pas_sync_lag_seconds{table=...}` and the `PasMigrationDeltaLagHigh` alert. Set
`PAS_MIGRATION_DELTA_SYNC_PAUSED=true` to pause forwarding; reset it to `false` to resume. The source
run-start timestamp is persisted as `synced_through`; never manually advance a watermark to hide
lag. Legacy POLNEW/POLRNW are expected to update coverages in the same unit of work as
POLICIES.LAST_UPDATED; a bounded rolling coverage-set hash sweep and reconciliation hashes detect
out-of-band writes. The sweep walks only DB2 `POLICIES` keys using
`pas.migration.delta-sync.coverage-sweep-size`, hashes each source and target coverage set with
`CanonicalRow`, persists its keyset cursor in `pas_batch.sync_watermark`, wraps when exhausted, and
re-syncs differing sets. This includes source policies with no coverages, which clear stale target
rows, while leaving PostgreSQL-only policies untouched.

Bulk and forward-delta writes set the transaction-local `pas.change_origin='forward-sync'` before
writing. The `pas.capture_change_log` trigger skips those writes so forward migration does not
replay its own changes back to DB2; service and owner writes without the tag remain logged. The
trigger stays `SECURITY INVOKER`, and pre-cutover `pas_sync` has no INSERT or sequence privilege on
`pas.change_log`, so an untagged sync write fails closed.

Reverse sync reads `pas.change_log` in one read-only `REPEATABLE READ` target transaction per pass
and captures `pg_current_snapshot()`. The first pass reads all rows; later passes select
`tx_id >= pg_snapshot_xmin(previous_snapshot)` rows that were not visible in that snapshot, using
an ID keyset. After applying idempotent MERGEs, each pass stores the captured snapshot and its xmin.
A transaction that commits late is applied once on the next pass, and drain stops when a pass
applies zero new rows. `changed_at` is retained for lag reporting, not as the change cursor. After
the drain, `pruneChangeLogStep` deletes rows whose `tx_id` is older than the stored reverse xmin in
bounded batches; monitor
`pas.sync.reverse.change_log.pruned` for the number of rows removed. The six row triggers record no
PII. For rollback, set modern-write flags to legacy, restart the policy service, drain reverse sync,
then reconcile.

## Reconciliation and parallel-run triage

Run `reconciliationJob` on demand before every cutover decision. Check the latest
`pas_batch.recon_run` status and its JSON/Markdown report in
`PAS_MIGRATION_RECON_REPORT_DIR`. `CLEAN` requires exact counts and grouped totals, plus matching
record hashes for policies, coverages, premiums, and holders. Holder fingerprints use HMAC-SHA256;
holder plaintext is decrypted only in memory and is not written to difference rows or reports.

Canonical hashes use business columns in DDL order, excluding PAS-only columns such as `row_version`,
`*_bidx`, `source_system`, and `decision_id`. The canonical text is the table name, followed by each
canonical field value separated by U+001F. NULL is `\N`; CHAR/VARCHAR trailing spaces are removed;
DECIMAL uses `stripTrailingZeros().toPlainString()` with zero represented as `0`; DATE uses
`yyyy-MM-dd`; TIMESTAMP uses UTC ISO-8601 with microseconds and `Z` (DB2 local timestamps are
converted using `pas.migration.source.zone`); SMALLINT/INTEGER values use decimal strings. Hashes
are lowercase hexadecimal SHA-256; policyholder rows use HMAC-SHA256 with the dedicated fingerprint
key.

Create a date-bounded cycle through `POST /api/batch/migration/parallel-run/cycles`, run
`parallelRunDiffJob` for every date, and review
`GET /api/batch/migration/parallel-run/differences`. Cycle detail is available at
`GET /api/batch/migration/parallel-run/cycles/{id}`; classification uses
`POST /api/batch/migration/parallel-run/differences/{id}/classification`; the latest
reconciliation is `GET /api/batch/migration/recon/runs/latest`. These endpoints require JWT scope
`batch:operate`; the triage operator is taken from JWT `sub`. See
[`parallel-run-triage.md`](parallel-run-triage.md). An
`APPROVED_FIX` must cite a real quirk-register row whose Decision contains `Fix`; `BUG` and
`DATA_ISSUE` require a note. Re-running a comparison marks absent differences not present and
resets triage only when the compared values changed. Extract difference details are digested to
avoid storing lines that may contain holder information. A cycle is `CLEAN` only when every date
ran and no present difference is `UNTRIAGED` or `BUG`.

## Cutover grants and rollback

Apply `pas-platform/db/grants/roles.sql` once, then `pre-cutover.sql` before starting the parallel
run. After the cutover decision, apply `post-cutover.sql` to make the PostgreSQL application the
writer and the legacy runtime read-only. These scripts define NOLOGIN roles; bind actual service
principals to them through the organization's database identity process.

For an isolated local rehearsal, first run
`docker compose --profile migration down -v`, then run `make rollback-rehearsal` from the repository root. It uses
`make dev-env` generated local-only keys, an ephemeral PostgreSQL 16 ACMEINS source and an isolated
PostgreSQL target. It bulk-loads, runs delta sync, sets new-business, renewal, and endorsement routes
to `new`, performs authenticated endorsement and renewal API writes, drains reverse sync, and
requires zero reverse lag and clean reconciliation. It then restores all routes to `legacy`, verifies
that disabling the migration feature removes migration jobs, and prints the measured RPO 0 / RTO
≤4-hour verdict with nonzero exit on failure. The full log is stored at
`~/ab529/rollback-rehearsal.log`; no production database or production key is used.

If a rehearsal or production cutover is rolled back, disable `PAS_MIGRATION_ENABLED`, keep online
routes on the legacy path, stop migration scheduling, and use DB2 as the source of truth. Preserve
the last watermarks and reconciliation report for diagnosis; do not delete or rewind them as a
rollback shortcut.

## Monitoring

Import `pas-platform/observability/grafana/pas-migration.json` and load
`pas-platform/observability/prometheus/pas-migration-alerts.yml` into the Prometheus rule set. The
dashboard covers per-table delta lag and lag alerts, reverse lag, reconciliation differences and
age, parallel-run untriaged and BUG counts, and batch job failures. See
`pas-platform/observability/README.md` for metric names.
