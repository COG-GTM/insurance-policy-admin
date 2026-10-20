# DB2 → PostgreSQL data migration, sync, reconciliation and rollback (AB-529)

Status: engineering done and exercised locally against a DB2 stand-in.
**Pending: Mainframe/Platform/Ops** for CDC against real DB2, the full-billing-cycle parallel run and
the production rollback rehearsal. None of those has happened.

## Where it runs

Everything is a job or CLI profile inside the existing `pas-batch` deployable (package
`com.acme.insurance.pas.batch.migration`). ADR-0001 allows one policy service plus one batch app, so
there is no `pas-migration` service. All beans are off unless `pas.migration.enabled=true`.

| Piece | Spring Batch job / CLI command | What it does |
|---|---|---|
| Bulk load | `db2BulkLoadJob` / `bulk-load` | One chunk step per table in FK order: POLICY_HOLDERS, POLICIES, COVERAGES, PREMIUMS, ENDORSEMENTS, UNDERWRITING_DECISIONS. Keyset paging on the DB2 primary key (`JdbcPagingItemReader`), upsert per chunk. |
| Delta sync | `db2DeltaSyncJob` / `delta-sync` | Timestamp/watermark deltas (below). Skips when paused. |
| Reconciliation | `migrationReconciliationJob` / `reconcile` | Counts, totals, record hashes → JSON + Markdown report, `pas.migration_check_runs`, gauges. |
| Parallel run diff | `parallelRunDiffJob` / `parallel-run` | See [parallel-run.md](parallel-run.md). |
| Rollback switch | `pause-sync` / `resume-sync` | Sets `pas.migration_sync_control.paused`. |

Schedule (`pas.migration.schedule.enabled`, one replica, `pas.schedule.zone`): delta every 15 min,
reconciliation 05:00, parallel-run diff 06:00 for the previous business date.

CLI: `java -jar pas-batch.jar --spring.main.web-application-type=none --pas.migration.cli=bulk-load,delta-sync,reconcile [--pas.migration.business-date=YYYY-MM-DD]`.
Exit 0 = every check PASS, 3 = unexplained differences or incomplete, 1 = failure.

### Configuration

| Key | Env | Default |
|---|---|---|
| `pas.migration.enabled` | `PAS_MIGRATION_ENABLED` | `false` |
| `pas.migration.source.url` / `username` / `password` | `PAS_MIGRATION_SOURCE_URL` / `_USERNAME` / `_PASSWORD` | none (secrets store; read-only grant on ACMEINS) |
| `pas.migration.source.schema` | `PAS_MIGRATION_SOURCE_SCHEMA` | `ACMEINS` |
| `pas.migration.chunk-size` | `PAS_MIGRATION_CHUNK_SIZE` | `500` |
| `pas.migration.delta-overlap` | — | `PT10M` |
| `pas.migration.report-dir` | `PAS_MIGRATION_REPORT_DIR` | `/var/lib/pas-batch/migration` |
| `pas.migration.legacy-output-dir` | `PAS_MIGRATION_LEGACY_OUTPUT_DIR` | `/var/lib/pas-batch/legacy-outputs` |
| `pas.migration.triage-register` | `PAS_MIGRATION_TRIAGE_REGISTER` | `parity/parallel-run/triage-register.json` |
| `pas.pii.encryption-key` / `blind-index-key` / `key-id` | `PAS_PII_*` | same keys as `pas-policy-service` |

The DB2 JDBC driver (`com.ibm.db2:jcc`) is on the `pas-batch` runtime classpath. It has not been
used against a real DB2 in this ticket.

## Mapping and PII

Column names are the same on both sides (AB-521 V2). POLICY_HOLDERS `DATE_OF_BIRTH`, `SSN_LAST4`,
`TAX_ID` and `CREDIT_SCORE` are written only as AES-256-GCM ciphertext (`*_enc`) plus HMAC blind
indexes (`tax_id_bidx`, `ssn_last4_bidx`), using the `pas-common` `PiiCipher` / `BlindIndexer` with the
same purposes and plaintext formats as the policy-service converters (V4). Plaintext never reaches
`pas` or the logs. UNDERWRITING_DECISIONS has a surrogate `decision_id` in the target (Q-20), so rows
are matched on the DB2 key `(policy_number, decision_date)` and never duplicated. TERRITORY_FACTORS
is not loaded: PREMBAT never reads it ([Q-03](quirk-register.md#q-03)) and filling the `FILED` factor
set is a business decision (OQ-04). `pas.policy_seq` is not moved here; it must be set above DB2's
POLICY_SEQ at cutover (proposed follow-up below).

Re-runs are safe: every write is `INSERT … ON CONFLICT (pk) DO UPDATE`, so the load converges on DB2.
A failed load restarts from the last committed chunk (Spring Batch metadata in `pas_batch`).

`pas.policies.version` (AB-525, Flyway V6) is not a DB2 column. Bulk load inserts it as 0 (the column
default) and a sync update bumps it only when a mapped column actually changed, so re-runs leave it
alone and a JPA writer holding an older version gets an optimistic-lock failure instead of
overwriting a synced change. The migration writes through plain JDBC and never touches `pas.outbox`,
so bulk load and delta sync publish no events. The tables for this ticket are in Flyway V7.

## Ongoing sync: timestamp deltas now, CDC recommended

| Table | Change column | Delta strategy |
|---|---|---|
| POLICIES, POLICY_HOLDERS | `LAST_UPDATED` (TIMESTAMP) | `>= watermark − overlap` |
| ENDORSEMENTS | `PROCESSED_DATE` (TIMESTAMP, insert time) | same |
| UNDERWRITING_DECISIONS | `CREATED_TIMESTAMP` (insert time) | same |
| PREMIUMS | `CALC_DATE` (DATE) | whole watermark day re-read |
| COVERAGES | none | re-synced for every policy whose `LAST_UPDATED` moved |

Watermarks are in `pas.migration_watermarks`; the bulk load seeds them with `MAX(change column)`
captured before its first read, so writes during the load are caught by the next delta.

Limits of the fallback, all caught by the daily reconciliation rather than silently lost:
- DB2 deletes are invisible (show as `EXTRA_IN_TARGET`).
- Updates that do not move the change column (a COVERAGES change without touching its policy, an
  UPDATE on ENDORSEMENTS/UW decisions) are missed until the next bulk-load re-run (idempotent).
- Precision depends on DB2 programs setting `LAST_UPDATED`; POLNEW/POLRNW/POLEND do, batch fixes may not.
- Each delta is a full read of the change column range: extra MIPS on DB2.

**Recommendation:** for production use log-based CDC from the DB2 recovery log (IBM InfoSphere Data
Replication / IIDR, or Debezium's Db2 connector if the mainframe team can expose the ASN capture
tables), feeding the same `TargetWriter` upserts. CDC captures deletes and every update, gives a
`cdc_position` for `pas_shadow`, and costs fewer MIPS. Choice and licensing are **Pending: Mainframe
team**. The timestamp delta stays as the fallback and for local/CI runs.

## Reconciliation

Daily (and on demand) per business date:
- row counts for all six migrated tables;
- by policy type and branch: POLICIES `TOTAL_PREMIUM`, `COVERAGE_LIMIT` and row count; COVERAGES
  `PREMIUM`, `COVERAGE_LIMIT`; PREMIUMS `TOTAL_PREMIUM`;
- SHA-256 record hashes for POLICIES, COVERAGES and PREMIUMS over a canonical form (DDL column order,
  `|` separator, `\N` for NULL, CHAR right-trimmed, decimals at DDL scale, ISO dates, timestamps to
  microseconds) plus a digest per table. Changed rows report the differing fields.

Every difference goes through the triage register ([parallel-run.md](parallel-run.md)).
Visibility for the team:
- `<report-dir>/reconciliation/<date>/<run>.json|.md` and `latest.json|md` (post `latest.md` to the
  team channel from the scheduler host; wiring the channel is a proposed follow-up);
- `pas.migration_check_runs` (one row per run with classification counts);
- `GET /actuator/migration` on `pas-batch`, opt-in (add `migration` to the actuator exposure only behind the internal network policy; pas-batch has no auth) (latest summaries, consecutive passing days, sync pause
  state, watermarks; counts only, no record values);
- Prometheus gauges `pas_migration_unexplained{check}`, `pas_migration_differences{check,classification}`,
  `pas_migration_check_passed{check}`, `pas_migration_last_run_seconds{check}`. Alert on
  unexplained > 0 or last run older than 26 h.

Local: `make reconcile` (Compose PostgreSQL + pas-policy-service for Flyway, DB2 stand-in schema
`acmeins_src` seeded from the java-facade seed via `pas-platform/dev/migration/acmeins_seed.py`).
Tests: `MigrationIT` uses H2 in DB2 mode as the source; `make parallel-run` runs it with `ParallelRunIT`.

## Rollback

Goal: point back at DB2 inside the agreed window (proposed one billing cycle for the reverse window,
cut-back itself in under 60 min — **Pending: Ops** to agree the numbers).

1. **Route flags → `legacy`** for every capability (ADR-0001 D7) through the reviewed route-flag
   config change. CICS/JCL become authoritative again; the facade serves `/api/v1` reads.
2. **Stop sync**: `--pas.migration.cli=pause-sync` (or set `pas.migration_sync_control.paused`).
   Stop the `pas-batch` scheduler for premium/extract jobs if they were authoritative.
3. **Reverse-delta check**: before the flip only the sync writes to `pas`, so `pas` must hold nothing
   DB2 lacks: zero `EXTRA_IN_TARGET` rows and zero `pas` rows changed after the flip time. After a
   capability has been `new`, rows written by the new platform since its flip must be replicated back
   (reverse replication, ADR-0001 D8) and the reconciliation re-run with DB2 as the reference until it
   passes.
4. **Re-entry**: fix, `resume-sync`, run delta + reconciliation until PASS, then move flags forward again.

`make rollback-rehearsal` runs these steps against local Compose and writes a result file.
Last local result: [rollback-rehearsal-local.md](rollback-rehearsal-local.md) — **local rehearsal
only**. The gateway is a local file there, DB2 is a PostgreSQL schema, and reverse replication
tooling does not exist yet. Production rehearsal: **Pending: Mainframe/Platform/Ops**.

## Sign-offs

- CDC product and DB2 access: Pending: Mainframe team.
- Full billing cycle parallel run with zero unexplained differences: Pending: Mainframe/Platform/Ops.
- Production rollback rehearsal and window: Pending: Ops.
- Architecture review of the migration components (ARB): Pending: ARB.
