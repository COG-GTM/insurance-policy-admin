# Premium recalculation batch runbook (AB-523)

`premiumRecalculationJob` replaces [`PREMIUM-BATCH.jcl`](../../jcl/PREMIUM-BATCH.jcl) STEP010–030
and [`PREMBAT`](../../cobol/programs/PREMBAT.cbl). The legacy job header identifies CA-7 PAS0050
as a daily 01:00 run. Its schedule definition, predecessors, calendars, and rerun procedure are
missing (M-05); confirm them with operations before cutover. The
[integrations register](../modernization/integrations.md) tracks the legacy TSO alert as I-08 and
scheduler ownership as I-09.

PREMBAT rates only active (`AC`) policies by policy number. The new job calls `pas-rating`, writes
one premium row per policy with `coverage_seq=1`, and leaves `pas.policies.total_premium` unchanged
(Q-36; see the [quirk register](../modernization/quirk-register.md)). It uses bounded chunks and
restartable policy-number partitions. The daily extract PAS0100
is listed at 23:00, before PAS0050 at 01:00; it therefore does not include that night's premium
calculation, and premium writes do not create extract deltas (Q-36/Q-38). Confirm downstream
ordering before changing either schedule.

## Route and database ownership

`pas.routing.premium-batch` (environment variable `PAS_ROUTING_PREMIUM_BATCH`) defaults to
`legacy`.

| State | Behavior |
|---|---|
| `legacy` | Job exits `DISABLED`; no premium writes, report, or run summary. Leave CA-7 enabled. |
| `shadow` | Writes premium rows to `pas_shadow` and report rows to `pas_shadow.premium_run_report`; does not write `pas`. |
| `new` | Writes to `pas.premiums`. Use only after cutover grants are issued. |
| `retired` | Writes to `pas.premiums`; legacy processing is assumed retired. |

Follow [ADR 0001 D4/D5](../adr/0001-pas-target-architecture.md) for privileges. Shadow requires
write access to `pas_shadow` and `pas_batch` only. The `pas_shadow` tables are owned by
pas-policy-service migrations under D4; AB-523 adds them in V80. New ownership requires
`INSERT`/`UPDATE` on `pas.premiums`, coordinated at cutover AB-530; never grant or enable that write
before the cutover decision.

## Configuration

| Property / environment variable | Default | Purpose |
|---|---|---|
| `pas.batch.cli.job` | — | CLI job name: `premiumRecalculationJob`, `dailyExtractJob`, or `monthlyExposureJob`. |
| `pas.batch.cli.business-date` | Current date in configured zone | Identifying ISO `yyyy-MM-dd` rating/calc date. |
| `pas.batch.cli.rerun` | — | Optional identifying long; use a new value to create a new execution instance. |
| `PAS_ROUTING_PREMIUM_BATCH` | `legacy` | Route state. |
| `PAS_BATCH_PREMIUM_ZONE` | `America/New_York` | Business-date and scheduler zone. |
| `PAS_BATCH_PREMIUM_GRID_SIZE` | `8` | Number of active-policy partitions. |
| `PAS_BATCH_PREMIUM_CHUNK_SIZE` | `500` | Chunk and reader page size. |
| `PAS_BATCH_PREMIUM_CONCURRENCY` | Grid size | Concurrent partition workers. |
| `PAS_BATCH_PREMIUM_SKIP_LIMIT` | `1000` | Maximum skippable policy failures per worker step. |
| `PAS_BATCH_PREMIUM_REPORT_DIR` | `/var/lib/pas-batch/reports` | Root for per-execution PREMRPT files. |
| `PAS_BATCH_PREMIUM_SCHEDULER_ENABLED` | `false` | Enable the in-app scheduler only when `true`. |
| `PAS_BATCH_PREMIUM_SCHEDULE` | `0 0 1 * * *` | Daily 01:00 cron in the configured zone. |
| `PAS_DB_URL`, `PAS_DB_USERNAME`, `PAS_DB_PASSWORD` | — | PostgreSQL connection. `PAS_DB_POOL_SIZE` defaults to 16. |

Run exactly one schedule: the in-app scheduler or the example Kubernetes CronJob. Do not enable
both. The example manifest is not applied by this change. The legacy header says daily 01:00 EST;
the configured IANA zone observes daylight saving time, so confirm the intended summer behavior.
The image provisions `/var/lib/pas-batch/reports` for UID 10001. Compose mounts the named
`pas-batch-reports` volume there; the CronJob example mounts the `pas-batch-reports` PVC and sets
`PAS_BATCH_PREMIUM_REPORT_DIR` to that path. Durable storage writable by the batch UID/GID is
required if reports must survive the CronJob pod.

## Start and stop

Start a run from `pas-platform`:

```sh
java -jar pas-batch/target/pas-batch-*.jar \
  --spring.main.web-application-type=none \
  --pas.batch.cli.job=premiumRecalculationJob \
  --pas.batch.cli.business-date=2025-02-10
```

The CLI exit codes are `0` completed without item errors, `4` completed with item errors, `3`
disabled by the legacy route, `1` failed/launch error, and `2` unknown job. Stop by cancelling the
active job execution through the approved operations control plane; do not kill worker threads or
delete Spring Batch metadata. The route guard prevents overlapping premium job executions.

## Rerun and restart

For a completed date, set a new `rerun` value to create a distinct job instance:

```sh
--pas.batch.cli.rerun=2
```

Reruns upsert the premium row and are idempotent for the policy term (Q-05). In `shadow`, a new
rerun first deletes only that business date's rows from `pas_shadow.premiums`, then writes the
currently active policies; rows for other business dates remain. This cleanup is performed by the
route-guard step, so a restart of the same failed instance does not repeat it and preserves
committed shadow rows. In `new`, reruns remain upsert-only: the PAS primary key
`(policy_number, coverage_seq, term_effective_date)` excludes calculation date, so a later business
date in the same term replaces an existing PAS row, while rows for policies no longer active remain.
PREMBAT does not delete prior rows either. A failed execution resumes by launching the same
parameters; Spring Batch reuses completed partitions and resumes the failed partition from its last
committed chunk. Do not assign a new `rerun` value when restarting a failed instance.

PREMRPT is written to
`<report-dir>/premiumRecalculationJob/<businessDate>/<jobExecutionId>/PREMRPT.D<yyMMdd>.txt`.
This per-execution directory avoids the fixed-name allocation collision (Q-40). Records are 132
characters, space-padded, LF-delimited, ISO-8859-1. Premiums render as `PIC 9(9).99`-style text,
for example `000000904.75` (Q-47).

## Errors and observability

Only `PremiumOverflowException`, `DataIntegrityViolationException`, and `PolicyRatingException` are
skipped. `RatingConfigurationException`, connection/transient errors, and other exceptions fail the
job. Skips record `ERROR` plus the exception class name only; logs include the policy number and
exception class, never the exception message or holder data. Exceeding the skip limit fails the
worker and leaves the job restartable.

`pas_batch.premium_run_summary` stores one summary per job execution. `pas_batch.premium_run_item`
stores the restart-safe per-policy outcome. Structured `pas.batch.premium.run.summary` logs include
read, updated, skipped, and error totals. Item errors increment
`pas_batch_premium_run_errors_total`; failed jobs also increment
`pas_batch_job_failures_total{job="premiumRecalculationJob"}`. Failure or item errors emit one
structured `alert=PREMIUM_BATCH_FAILED`; alert delivery is **Pending ops wiring** (I-08).

Use the Spring Batch metadata and run summary before deciding whether to restart. For an item error,
inspect the source row and exception type. A restart re-executes failed partitions only, so an
`ERROR` item from a completed partition remains an error and is still counted in that instance's
summary and report. After correcting a skipped policy, launch a fresh rerun with a new
`rerun=<n>` value to scan every active policy again; restarting the same instance will not retry
that completed partition. A completed-with-errors run returns CLI code 4 and should be reviewed
before cutover.

## Backout and retention

Back out by setting `premium-batch` to `legacy`, stopping the new schedule, and re-enabling CA-7
PAS0050 after confirming the legacy chain. In `legacy`, the new job does not write. Retain
`premium_run_item`, `premium_run_summary`, reports, and Spring Batch metadata according to the
approved batch audit/retention policy; the report reader and retention duration remain open
(OQ-20; see [open questions](../modernization/open-questions.md)).

## Load-test evidence and open questions

The default load-test size is 250,000 policies; the actual production active-policy and coverage
book size is unknown (OQ-23; see [open questions](../modernization/open-questions.md)). The local
Testcontainers run processed 225,000 active policies (two coverages per policy) in 26.332 seconds
excluding data generation, with grid 8, chunk 500, and concurrency 8. The host was Ubuntu 22.04.5
with 8 Intel Xeon vCPUs and 31 GiB RAM; Docker
29.7.2 ran `postgres:16-alpine` (PostgreSQL 16.15). Production-sized load verification remains
pending until OQ-23 is answered. Do not extrapolate linear throughput without validating the
database, storage, and concurrency limits of the target environment.
