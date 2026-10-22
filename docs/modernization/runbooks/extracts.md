# Claims and Actuarial extract runbook (AB-527)

The daily job reads `pas` and creates policy and coverage files; the monthly job creates the
Actuarial exposure CSV. `pas-batch` owns only `pas_batch` migrations. Ensure the `pas` schema has
already been migrated by pas-policy-service and is available to the batch datasource.

## Run, rerun, and restart

From `pas-platform`, run either job with a business date:

```sh
./mvnw -pl pas-batch -am package
java -jar pas-batch/target/pas-batch-0.1.0-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --pas.batch.cli.job=dailyExtractJob \
  --pas.batch.cli.business-date=2025-02-10
```

Use `monthlyExposureJob` for the Actuarial extract. Omitting the business date uses today's date in
`pas.batch.extract.zone` (default `America/New_York`). Completed dates are identifying job
parameters; add `--pas.batch.cli.rerun=1` (or another new number) to create a distinct execution.
Spring Batch restarts a failed execution by launching the same job with the same identifying
parameters. The CLI returns 0 only for a completed job and 1 for a failed/non-completed job.

Local files are isolated under
`<work-dir>/<job-name>/<business-date>/<job-execution-id>/`. Defaults to
`/var/lib/pas-batch/extracts`; avoid sharing an execution directory between runs.

## Configuration

| Property / environment variable | Purpose |
|---|---|
| `PAS_DB_URL`, `PAS_DB_USERNAME`, `PAS_DB_PASSWORD` | PostgreSQL datasource; reader queries `pas`, batch metadata and delivery records use `pas_batch` |
| `PAS_BATCH_EXTRACT_WORK_DIR` | Per-execution local output root |
| `PAS_BATCH_EXTRACT_ZONE` | Business-date zone; defaults to `America/New_York` |
| `PAS_ROUTING_DAILY_EXTRACT` | `legacy` (default), `new`, or `retired` |
| `PAS_ROUTING_MONTHLY_EXPOSURE` | `legacy` (default), `new`, or `retired` |
| `PAS_SFTP_CLAIMS_HOST`, `PAS_SFTP_CLAIMS_PORT`, `PAS_SFTP_CLAIMS_USER` | Claims target |
| `PAS_SFTP_CLAIMS_PRIVATE_KEY_FILE`, `PAS_SFTP_CLAIMS_KEY_PASSPHRASE_FILE`, `PAS_SFTP_CLAIMS_KNOWN_HOSTS_FILE` | Claims public-key auth and pinned host key |
| `PAS_SFTP_ACTUARIAL_HOST`, `PAS_SFTP_ACTUARIAL_PORT`, `PAS_SFTP_ACTUARIAL_USER` | Actuarial target |
| `PAS_SFTP_ACTUARIAL_PRIVATE_KEY_FILE`, `PAS_SFTP_ACTUARIAL_KEY_PASSPHRASE_FILE`, `PAS_SFTP_ACTUARIAL_KNOWN_HOSTS_FILE` | Actuarial public-key auth and pinned host key |
| `PAS_BATCH_EXTRACT_RETRY_MAX_ATTEMPTS`, `PAS_BATCH_EXTRACT_RETRY_INITIAL_BACKOFF`, `PAS_BATCH_EXTRACT_RETRY_MULTIPLIER`, `PAS_BATCH_EXTRACT_RETRY_MAX_BACKOFF` | Whole-file-set retry policy; defaults 3, 5s, 2, and 60s |
| `PAS_BATCH_EXTRACT_SCHEDULE_ENABLED` | Enables scheduler only when `true`; default `false` |
| `PAS_BATCH_EXTRACT_DAILY_CRON`, `PAS_BATCH_EXTRACT_MONTHLY_CRON` | Defaults `0 0 23 * * *` and `0 0 2 1 * *` |

Configure targets only in environments that deliver. A target is validated when selected for
delivery; incomplete configuration fails that delivery. Authentication is public-key-only. Host
keys must be pinned; there is no accept-any-host fallback.

`legacy` generates files and logs a delivery-skipped event, but performs no SFTP and creates no
delivery rows. `new` generates and delivers. `retired` fails the job before extraction; the scheduler
does not launch a retired capability. The route configuration is pending coordinator reconciliation
with pas-common `RouteFlags` (AB-524).

## Delivery monitoring and recovery

Failures increment Prometheus counter `pas_batch_job_failures_total{job="..."}` and emit one
structured `pas.batch.job.failed` event with exception class names only. Job logs never contain
record data. Check the delivery state with:

```sql
SELECT job_name, job_execution_id, business_date, target, remote_path, layout_version,
       record_count, byte_count, sha256, status, attempts, failure_type, started_at, completed_at
FROM pas_batch.extract_delivery
WHERE business_date = DATE '2025-02-10'
ORDER BY started_at, remote_path;
```

For a failed execution, inspect its step/job status and exception type, fix the configuration or
upstream cause, then restart with the same identifying parameters. A completed delivery can be
replayed with a new `rerun` value; file replacement is atomic where supported and consumers retain
the same fixed remote names.

## Consumer pickup contract

Consumers must ignore a file until its matching `<file>.done` marker exists. Verify its `layout`,
`records`, `bytes`, `sha256`, `businessDate`, and `jobExecutionId`; verify `<file>.sha256` using
sha256sum-compatible format before importing. The marker is written last. A fixed remote file is
replaced via a temporary upload and rename, so readers should open only after the marker is present.

Do not use the local filenames as consumer contracts. Daily local files are `policy_extract.dat`
and `coverage_extract.dat`; local monthly output is date-qualified.
