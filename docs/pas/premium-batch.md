# Premium recalculation batch (AB-523)

Spring Batch job `premiumRecalculation` in `pas/pas-batch` replaces CA-7 job `PAS0050`
(`jcl/PREMIUM-BATCH.jcl`, COBOL `PREMBAT`). It rates every active policy with the shared rating
component (`pas-rating`, AB-522), writes the `PREMIUMS` row per policy, records each policy's outcome
and writes the run report. The legacy JCL and COBOL stay untouched until retirement.

## What the job does

| Step | What | Legacy equivalent |
|---|---|---|
| `premiumRating` (partitioned) | Splits the active book (`POLICY_STATUS = 'AC'`) into `pas.premium.partitions` contiguous policy-number ranges. Each worker (`premiumRatingPartition:partitionN`) reads its range in policy order, rates each policy and commits every `pas.premium.chunk-size` policies. | `3000-PROCESS-POLICIES` cursor loop, one unit of work (Q-38) |
| rating | `RatingRequest.of(policyType, businessDate)` against **one** `PremiumRater.snapshot()` taken when the job starts, shared by all partitions and kept on restart of the same execution, so every policy in a run sees the same rates. | `3200-CALCULATE-PREMIUM` |
| write | Upsert of `PREMIUMS` by key (policy, coverage seq 1, term effective date): update if present, insert otherwise. `INSTALLMENT_AMT` is never written, as in the PREMBAT INSERT (new rows get the column default, a rerun keeps the existing amount). `CALC_BY = 'PREMBAT'`, `CALC_DATE` = business date, installment code `AN`. Outcome per policy in `pasapp.premium_run_item` (V30) in the same transaction. | `3300-WRITE-PREMIUM-RECORD` INSERT (Q-26) |
| errors | A policy whose rating fails (e.g. overflow, Q-522-1) or whose write the database rejects for data reasons (`DataIntegrityViolationException`) is recorded as an error (`status = 'E'`, error class, SQLSTATE and message) and the run continues, up to `pas.premium.skip-limit`. Infrastructure errors (connection lost, deadlock not resolved) fail the partition and therefore the job. | First INSERT error ends the loop (Q-25) |
| `premiumReport` | Writes `ACME.PAS.REPORTS.PREMIUM.D<yymmdd>.txt` to `pas.premium.report-dir`: header, one 132-byte line per policy in policy order, totals READ / UPDATED / ERRORS from `premium_run_item`. Written to a temp file and moved atomically, so a rerun replaces it. | `PREMRPT` (LRECL 132) |
| status | `COMPLETED` (exit 0), `COMPLETED_WITH_ERRORS` (exit 0, warning alert), `FAILED` (exit non-zero, page). | RC 0/4/16 and an inverted TSO `SEND` (Q-28) |

The report keeps the COBOL layout byte for byte, including `WS-FINAL-PREMIUM` as six raw packed-decimal
bytes (COMP-3, Q-29 preserve) in ISO-8859-1. Whether production really prints packed bytes is FU-13;
`PremiumReport` is the only place to change if the sample shows a display format.

## Configuration

| Property | Env var | Default | Notes |
|---|---|---|---|
| `pas.premium.report-dir` | `PAS_PREMIUM_REPORT_DIR` | (none) | Required; the job refuses to start without it. |
| `pas.premium.zone` | `PAS_PREMIUM_ZONE` | `America/New_York` | Zone of the default business date (CA-7 ran on Eastern time). |
| `pas.premium.partitions` | `PAS_PREMIUM_PARTITIONS` | 8 | Parallel workers; needs as many DB connections plus 2. |
| `pas.premium.chunk-size` | `PAS_PREMIUM_CHUNK_SIZE` | 500 | Policies per commit; a restart redoes at most one chunk per partition. |
| `pas.premium.skip-limit` | `PAS_PREMIUM_SKIP_LIMIT` | 1000 | Per partition; above it the job fails instead of reporting thousands of errors. |

Rates and factors come from the AB-522 reference tables (`pas.rating.*`, `docs/pas/rating.md`).

## Running

Scheduled: `pas/deploy/premium/cronjob.yaml`, daily 01:00 America/New_York (the PAS0050 slot),
`concurrencyPolicy: Forbid`, `PAS_BATCH_JOB=premiumRecalculation`. Alerts: `pas/deploy/premium/alerts.yaml`.

Manual run or another business date (same image):

```
PAS_BATCH_JOB=premiumRecalculation PAS_PREMIUM_REPORT_DIR=/var/pas/reports/premium \
  java -jar pas-batch-exec.jar businessDate=2025-03-01
```

### Same-day rerun

Safe by design (Q-26 fix). A completed business date cannot be launched again with the same
parameters, so the scheduler's incrementer (or the operator) adds `rerun=<n>`: the rerun is a new job
instance that re-rates every active policy, replaces their `PREMIUMS` rows (no duplicate key), gets
its own `premium_run_item` rows and replaces the day's report. Rerunning never creates a second row
per policy and term.

```
java -jar pas-batch-exec.jar businessDate=2025-03-01 rerun=1
```

## Failure and restart

1. The page names the job, business date and reason (`pas.premium.alert ...`). Check the pod log and
   `BATCH_STEP_EXECUTION` for the failed partition.
2. Fix the cause (database, network, a bad reference row).
3. Restart with the **same** parameters (no `rerun`): `java -jar pas-batch-exec.jar businessDate=<same date>`.
   Spring Batch resumes the same instance: completed partitions are skipped, failed partitions resume
   after their last committed chunk, and the report counts the whole run. The Kubernetes Job's pod
   retry (`backoffLimit: 1`) does exactly this automatically.
   If the rating reference data (base rates, parameters, factors) changed since the failed
   execution, the restart fails at once with "rating reference data changed ... launch a same-day
   rerun": the committed chunks were rated with the old rates and one run must not mix two rate
   versions. Launch `rerun=<n>` instead, which re-rates the whole book with the current rates.
4. If the execution is left `UNKNOWN` (the database connection died during rollback), mark it failed
   first: `UPDATE pasapp.batch_job_execution SET status = 'FAILED', exit_code = 'FAILED' WHERE
   job_execution_id = <id>` and the same for its `STARTED`/`UNKNOWN` step executions, then restart.
   This needs the DBA change process (FU-12).

### Completed with errors

`COMPLETED_WITH_ERRORS` raises a ticket, not a page (`pas.premium.warning`). The failing policies:

```sql
SELECT policy_number, error_code, detail
  FROM pasapp.premium_run_item
 WHERE job_instance_id = <id> AND status = 'E'
 ORDER BY policy_number;
```

After fixing the data, a same-day `rerun=<n>` re-rates the whole book (cheap, idempotent).

## Performance

`PremiumJobLoadIT` seeds a synthetic book in PostgreSQL (Testcontainers), runs the job with the
production defaults and asserts the projected full-book time is under 30 minutes; the result is
written to `pas/pas-batch/target/premium-load-test.txt`.

```
make premium-load-test                          # 50,000 policies, projected to 1,000,000
make premium-load-test POLICIES=1000000         # full-book size, measured not projected
```

Measured results are recorded below. Production volume, DB2 for z/OS latency and the production
pod sizing are not available here: the load test against the production-sized DB2 is pending (see
sign-off table).

| Date | Where | Policies | Elapsed | Policies/s | Full book (1,000,000) |
|---|---|---|---|---|---|
| 2026-11-05 | Dev VM, 8 vCPU / 31 GiB, PostgreSQL 16 Testcontainer, 8 partitions, chunk 500 | 50,001 | 1.98 s | 25,215 | 39.7 s projected |
| 2026-11-05 | same | 1,000,001 | 19.2 s | 52,165 | 19.2 s measured |

Both are far under the 30-minute target on PostgreSQL. The production figure on DB2 for z/OS with
the real book and network latency is still required (pending).

If the run slows down: raise `PAS_PREMIUM_PARTITIONS` (and the pool size), check the partitions are
balanced (`BATCH_STEP_EXECUTION.READ_COUNT` per partition) and that `ix_premiums` / the
`POLICIES` primary key are used.

## Parity

* `make parity` runs suite `prembat-batch-control` through `PrembatBatchControlAdapter`, which runs
  this job against the Flyway-migrated reference database. Known differences KD-06 (Q-26: a
  same-day duplicate is replaced, not an error) and KD-07 (Q-25: the run continues after an error)
  are pending sign-off. Suite `prembat-rating` (AB-522) covers the rating to the cent.
* `PremiumJobIT` compares the PREMIUMS rows and the 132-byte report of case `report-totals` with the
  COBOL golden file byte for byte, and covers rerun, per-policy errors, restart and an empty book.

## Cut-over

1. Parallel run: run the job against a copy of the book after PAS0050 for at least 5 business days
   and reconcile `PREMIUMS` rows (all columns) and report totals; differences beyond KD-06/KD-07
   block cut-over.
2. Put PAS0050 on hold in CA-7, enable the CronJob. Rollback: suspend the CronJob, release PAS0050.
3. Retirement of `jcl/PREMIUM-BATCH.jcl` and `PREMBAT` follows the epic's decommission ticket;
   nothing is removed here.

## Sign-off (pending)

| Item | Who | Status |
|---|---|---|
| Q-25 / KD-07 continue after a per-policy error | Mainframe team | pending |
| Q-26 / KD-06 idempotent upsert, how production avoids -803 today (FU-11) | Actuarial / Pricing + Mainframe team | pending |
| Q-28 success/warning/failure alerting, paging target, CA-7 calendar and predecessors (FU-12) | Mainframe operations / CA-7 | pending |
| Q-29 report format from a masked production sample (FU-13) | Mainframe team | pending |
| Q-38 chunked, partitioned, restartable run | Mainframe team | pending |
| Full-book load test on production-sized DB2 | Platform / DBA | pending |
| Parallel-run reconciliation and PAS0050 hold | PAS product owner + Operations | pending |
