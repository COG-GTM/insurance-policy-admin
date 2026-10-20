# Runbook: premium recalculation (`premiumRecalculationJob`)

Replaces `jcl/PREMIUM-BATCH.jcl` (program PREMBAT, CA-7 job **PAS0050**, daily 01:00 EST).
Ticket AB-523, epic AB-516. Code: `pas-platform/pas-batch`, package
`com.acme.insurance.pas.batch.premium`.

Status of the decisions behind this runbook (Q-22, Q-24, Q-25, Q-26, Q-35 in
[quirk-register.md](../quirk-register.md)): **Proposed — pending PO sign-off**. Operational
acceptance of this runbook: **Pending: Ops**.

## What the job does

| Step | What | Legacy equivalent |
|---|---|---|
| `premiumSingleRunGuard` | Fails the run if an earlier run for the same business date is still running (AB-527 `SingleRunPerDateTasklet`) | — |
| `premiumReportInitStep` | Creates the report directory; if it cannot, the run **FAILS** (exit 16) | PREMBAT `1000-INITIALIZE` (lost RC 16, Q-25) |
| `premiumRecalculationStep` | Splits active policies (`policy_status = 'AC'`) into `pas.batch.premium.partitions` policy-number ranges and processes them in parallel. Each partition: JDBC paging read in policy-number order → rate with the shared `PremiumRatingService` (tables loaded **once per run**, as of the run date) → upsert `pas.premiums` + report lines, committed every `chunk-size` policies | PREMBAT `2000`/`3000`/`3100`/`3200`/`3300` (single thread, cursor) |
| `premiumReportStep` | Writes `PREMRPT-<yyyymmdd>-<instance>.txt` with header, one `<policy> PREMIUM: <amount>` line per policy (policy-number order) and the totals `TOTAL POLICIES READ / UPDATED / ERRORS` | PREMBAT `4000-WRITE-SUMMARY` (amounts now edited, Q-24) |

Afterwards the job records one row in `pas_batch.premium_run_summary`, logs
`Premium run finished ... outcome=...`, updates metrics and alerts if needed (see Alert response).

PREMIUMS rows are identical to PREMBAT's for the same input (parity tests
`PremiumJobParityTest` against `parity/programs/prembat/golden.csv`), including
`CALC_BY = 'PREMBAT'`, `COVERAGE_SEQ = 1`, `CALC_DATE` = run date.

### Outcomes and exit codes

| Outcome | Batch status / exit status | CLI exit code | Alert |
|---|---|---|---|
| `COMPLETED` | `COMPLETED` / `COMPLETED` | 0 | none |
| `COMPLETED_WITH_ERRORS` — some PREMIUMS writes failed (data-integrity errors, counted in TOTAL ERRORS, skipped) | `COMPLETED` / `COMPLETED_WITH_ERRORS` | 4 (PREMBAT RC 4) | yes |
| `FAILED` — infrastructure/DB error, rating table problem, report directory, or more than `skip-limit` write errors in one partition | `FAILED` | 16 | yes |

Differences from PREMBAT that operators will notice: a rerun no longer fails with -803 but
replaces the rows (Q-22); a failed write no longer stops the run (Q-35); "completed with
warnings" is no longer sent for a clean run and a failure now alerts (Q-26).

## CA-7 dependency being replaced

| CA-7 / JCL | Replacement |
|---|---|
| Job PAS0050, schedule daily 01:00 EST | `pas.batch.premium.cron` (`PAS_BATCH_PREMIUM_CRON`), zone `pas.batch.premium.zone` (`PAS_BATCH_PREMIUM_ZONE`, default `America/New_York`). Default `-` = **disabled** |
| STEP010 `IKJEFT01` → PREMBAT, plan PREMPLAN, DB2 DBPD | pas-batch container, datasource `PAS_DB_URL` (PostgreSQL `pas` schema) |
| `PREMRPT` DD `PAS.PREMIUM.REPORT.D&LYYMMDD` | `PAS_BATCH_REPORT_DIR/PREMRPT-<yyyymmdd>-<instance>.txt` (mount a persistent volume) |
| STEP020 `IEFBR14` "send email" (no-op) | — |
| STEP030 `COND=(4,LT,STEP010)` TSO `SEND ... USER(PASADMIN)` | Shared `batch.alert` notifiers (AB-527 `BatchAlertNotifier`; default `BATCH ALERT` ERROR line on logger `pas.batch.alert`) + metrics `pas_batch_job_runs_total`, `pas_batch_premium_runs_total` |
| Predecessors / successors | None are visible in the repo (no CA-7 definitions are checked in, see [missing-artifacts.md](../missing-artifacts.md) and [OQ-07](../open-questions.md#oq-07)). **Pending: Ops / Mainframe scheduling** to confirm before PAS0050 is removed |

**Cut-over** (proposed in [open-questions.md](../open-questions.md), "AB-523"): keep the cron
disabled while DB2 is the system of record (before the flip only CDC writes to `pas`). At the
PREMIUMS cut-over: (1) remove PAS0050 from CA-7, (2) set
`PAS_BATCH_PREMIUM_CRON="0 0 1 * * *"`, (3) watch the first run with the checks below.

## Configuration

| Property | Env var | Default | Notes |
|---|---|---|---|
| `pas.batch.premium.cron` | `PAS_BATCH_PREMIUM_CRON` | `-` (off) | Spring cron, 6 fields |
| `pas.batch.premium.zone` | `PAS_BATCH_PREMIUM_ZONE` | `America/New_York` | also the default run date |
| `pas.batch.premium.partitions` | `PAS_BATCH_PREMIUM_PARTITIONS` | 8 | parallel policy-number ranges |
| `pas.batch.premium.chunk-size` | `PAS_BATCH_PREMIUM_CHUNK_SIZE` | 1000 | commit interval and page size |
| `pas.batch.premium.skip-limit` | `PAS_BATCH_PREMIUM_SKIP_LIMIT` | 10000 | write errors per partition before FAILED |
| `pas.batch.premium.report-dir` | `PAS_BATCH_REPORT_DIR` | `./reports/premium` | needs a persistent, writable volume |
| `spring.datasource.hikari.maximum-pool-size` | `PAS_BATCH_DB_POOL_SIZE` | 20 | keep > partitions + 2 |
| `pas.rating.factor-set` | `PAS_RATING_FACTOR_SET` | `PARITY` | `FILED` is empty; the run fails if selected (OQ-04) |
| `pas.security.oidc.issuer-uri` | `PAS_OIDC_ISSUER_URI` | empty | empty = operator API rejects every request |
| `pas.security.oidc.audience` | `PAS_OIDC_AUDIENCE` | empty | checked when set |

## Starting a run

One job instance per **run date** (job parameter `businessDate`, same name as the AB-527 extract jobs). Starting a date that already completed
is refused unless `rerun` is requested; starting a date whose last run failed **restarts** it.

**HTTP** (port 8081, bearer JWT with scope `batch:operate`):

```sh
# start today's run (date in pas.batch.premium.zone); 202 + jobExecutionId
curl -X POST -H "Authorization: Bearer $JWT" "https://<pas-batch>/api/batch/premium/runs"
# a given date
curl -X POST -H "Authorization: Bearer $JWT" "https://<pas-batch>/api/batch/premium/runs?runDate=2026-10-20"
# status and totals (poll until batchStatus is COMPLETED or FAILED)
curl -H "Authorization: Bearer $JWT" "https://<pas-batch>/api/batch/premium/runs/<jobExecutionId>"
```

Responses: 202 started, 409 `PREMIUM_RUN_CONFLICT` (already complete / already running / not
restartable), 400 invalid input, 401 no or invalid JWT, 403 JWT without `batch:operate`.

**CLI** (one-shot container or Kubernetes Job; exit code = table above):

```sh
java -jar pas-batch.jar --spring.main.web-application-type=none \
  --pas.batch.premium.cli.run=true --pas.batch.premium.cli.run-date=2026-10-20
```

Access to the CLI is whoever may run the pas-batch container (platform RBAC).

## Rerun (recalculate a date that completed)

Use after a rate correction or a data fix. Rows for the same
`(policy_number, coverage_seq, term_effective_date)` are **replaced** (Q-22), so a rerun is
safe at any time; the next daily run also replaces rows of policies in the same term.

```sh
curl -X POST -H "Authorization: Bearer $JWT" "https://<pas-batch>/api/batch/premium/runs?runDate=2026-10-20&rerun=true"
# or CLI: --pas.batch.premium.cli.rerun=true
```

A rerun is a new job instance with its own report file and summary row.

## Restart after a failure

1. Find the failed execution: alert payload (`jobExecutionId`) or
   `SELECT * FROM pas_batch.premium_run_summary ORDER BY job_execution_id DESC LIMIT 5;`
2. Read `failure_message` and the pas-batch log (`Encountered an error executing step`); fix the
   cause (DB availability, report volume, rating tables, bad data).
3. Restart — either form resumes from the last committed chunk of each unfinished partition;
   completed partitions are not re-run, and every policy is written exactly once
   (`PremiumJobRestartIT`):
   ```sh
   curl -X POST -H "Authorization: Bearer $JWT" "https://<pas-batch>/api/batch/premium/runs/<jobExecutionId>/restart"
   # or start the same runDate again, or CLI: --pas.batch.premium.cli.restart-execution-id=<id>
   ```
4. The restarted execution's report and summary row carry the **cumulative** totals of the date.

Do not delete rows from `pas_batch.BATCH_*` to "reset" a run: the restart state lives there. To
recalculate from scratch use a rerun.

## Alert response

Alerts go to every `BatchAlertNotifier` bean (shared with the AB-527 extract jobs; the default
logs `BATCH ALERT job=premiumRecalculationJob ...` at ERROR on logger `pas.batch.alert`; add a
bean to page or post to chat):

| `BatchAlert` | Raised by | Action |
|---|---|---|
| status `FAILED` | `JobFailureAlertListener` | Restart procedure above. If the cause is the rating tables (`RatingDataException`, empty factor set), escalate to rating/actuarial — do not change `PAS_RATING_FACTOR_SET` without sign-off |
| status `COMPLETED`, exitCode `COMPLETED_WITH_ERRORS` | premium job listener | PREMIUMS rows for the policies listed in the `PREMIUMS write failed policy=...` WARN lines were not written. Fix the data, then **rerun** the date |
| status `NOT_LAUNCHED`, exitCode `LAUNCH_FAILED` | scheduler / CLI | The run never started (DB down, bad parameters). Fix and start the run manually |

Metrics: `pas_batch_job_runs_total{job="premiumRecalculationJob",status}` (AB-527),
`pas_batch_premium_runs_total{outcome="COMPLETED"|"COMPLETED_WITH_ERRORS"|"FAILED"}`,
`pas_batch_premium_last_run_policies{result="read"|"updated"|"errors"}`,
`pas_batch_premium_run_duration_seconds{outcome}`. Suggested Prometheus rules (**Pending: Ops**
to wire into Alertmanager):

```yaml
- alert: PasPremiumRunNeedsAttention
  expr: increase(pas_batch_premium_runs_total{outcome!="COMPLETED"}[1h]) > 0
- alert: PasPremiumRunMissing   # once the cron is enabled
  expr: increase(pas_batch_premium_runs_total{outcome="COMPLETED"}[26h]) == 0
```

## Load test

No production volume data exists in the repo (the "about 4 hours" in the epic is the only
figure). The full-book size is an **assumption: 1,000,000 active policies** — an
order-of-magnitude book for a regional P&C carrier, rounded up so the test is conservative.
**Real volume: Pending: Ops** (see open-questions "AB-523").

Generator: `pas-platform/pas-batch/src/test/resources/loadtest/generate-book.sql`
(`:active` active policies + 10% cancelled ones that must be ignored, PREMBAT type mix).
Run it against a load-test database with `psql -v active=1000000 -f generate-book.sql`, or
through the test:

```sh
cd pas-platform
./mvnw -B -ntp -pl pas-batch verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=PremiumJobLoadIT -Dpas.loadtest.policies=1000000
# result appended to pas-batch/target/loadtest/premium-load.txt
```

CI runs the same test at 20,000 active policies as part of `./mvnw verify`.

Measured on the AB-523 development VM (8 vCPU, 31 GB RAM; PostgreSQL 16 in a local
Testcontainers container with default settings, same host, 8 partitions, chunk 1000):

| Active policies | Run time (job only) | Throughput | Target |
|---|---|---|---|
| 20,000 | 0.7 s | ~27,000 policies/s | — |
| 1,000,000 | 11.6 s | ~86,000 policies/s | < 30 min: **met** |

Caveats: database and job on the same host (no network latency), empty `pas.premiums` (first
run; a rerun also updates indexes), default PostgreSQL config. Re-measure on the target
platform with the real volume before go-live (**Pending: Ops**).
