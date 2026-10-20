# Extracts runbook: Claims daily and Actuarial monthly (AB-527)

`pas-batch` jobs that replace `DAILY-EXTRACT` and `MONTHLY-EXPOSURE`. Layouts:
[extract-layouts.md](extract-layouts.md). Architecture: ADR 0001 decision D6 (SFTP, key auth,
pinned host key, SHA-256), Pending: ARB.

## Jobs

| Job | Steps | Replaces | Default schedule (`America/New_York`) | Output (local) | Delivered to |
| --- | --- | --- | --- | --- | --- |
| `dailyExtractJob` | `policyExtractStep`, `coverageExtractStep`, `claimsDeliveryStep` | CA-7 `PAS0100`, job `DAILYEXT`, daily 23:00 EST | `PAS_DAILY_EXTRACT_CRON` = `0 0 23 * * *` | `<output-dir>/dailyExtractJob/<date>/policy_extract.dat`, `coverage_extract.dat` | `claims`: `policy_extract.dat`, `coverage_extract.dat` |
| `monthlyExposureJob` | `exposureExtractStep`, `actuarialDeliveryStep` | CA-7 `PAS0200`, job `MONEXP`, 1st of month 02:00 EST | `PAS_MONTHLY_EXPOSURE_CRON` = `0 0 2 1 * *` | `<output-dir>/monthlyExposureJob/<date>/exposure_monthly.csv` | `actuarial`: `exposure_monthly_<YYYYMMDD>.csv` |

Job parameter `businessDate` (ISO date) drives the policy `LAST_UPDATED` window and the output
folder. The scheduler uses today's date in `America/New_York`. Every launch is a new job instance
(`launchedAt`), so a rerun for the same date is allowed: local files are replaced atomically
(`.part` + rename) and the remote files are overwritten ([Q-32](quirk-register.md#q-32) fix; the JCL
abended on rerun because the datasets were allocated `NEW`).

## Configuration

All values come from the environment / secrets store; key files are mounted, never baked into
images. See `pas-platform/.env.example`.

| Property | Env var | Default | Notes |
| --- | --- | --- | --- |
| `pas.extract.output-dir` | `PAS_EXTRACT_OUTPUT_DIR` | `/var/lib/pas-batch/extracts` | Persistent volume; keep ≥ 35 days for reruns/audit |
| `pas.schedule.enabled` | `PAS_SCHEDULE_ENABLED` | `false` | Turn on per environment at cut-over. Run exactly **one** scheduling replica |
| `pas.schedule.daily-extract-cron` / `monthly-exposure-cron` | `PAS_DAILY_EXTRACT_CRON` / `PAS_MONTHLY_EXPOSURE_CRON` | see above | Spring 6-field cron |
| `pas.transfer.enabled` | `PAS_TRANSFER_ENABLED` | `true` (compose: `false`) | `false` = files kept locally, a WARN is logged; no other transport exists |
| `pas.transfer.destinations.<claims\|actuarial>.host/port/username` | `PAS_CLAIMS_SFTP_*`, `PAS_ACTUARIAL_SFTP_*` | port 22 | Startup fails if enabled and incomplete |
| `...private-key-file` / `...private-key-passphrase` | `PAS_*_SFTP_PRIVATE_KEY_FILE` / `_PASSPHRASE` | — | OpenSSH/PEM key; public-key auth only |
| `...known-hosts-file` | `PAS_SFTP_KNOWN_HOSTS_FILE` | — | Unknown or changed host keys are rejected |
| `...remote-directory` | `PAS_CLAIMS_SFTP_REMOTE_DIR` / `PAS_ACTUARIAL_SFTP_REMOTE_DIR` | `/claims/import`, `/actuarial/data` | Same folders as the legacy FTP steps |
| `pas.transfer.retry.*` | `PAS_TRANSFER_MAX_ATTEMPTS`, `PAS_TRANSFER_INITIAL_BACKOFF` | 4 attempts, 30 s ×2, max 5 min | Whole delivery retried |

## Delivery protocol (per destination)

1. Connect over SSH; verify the server key against `known_hosts`; authenticate with the key.
2. For each file: delete stale `<name>.done`, upload `<name>.part`, check remote size and
   re-read the remote bytes to compare SHA-256, rename to `<name>`, write `<name>.sha256`
   (`sha256sum` format).
3. When every file of the run is in place, write `<name>.done` (`file=`, `bytes=`, `sha256=`).
   **Consumers must pick up a file only after its `.done` exists** and should verify `.sha256`.

## Monitoring and alerting

| Signal | Meaning |
| --- | --- |
| `pas_batch_job_runs_total{job,status}` | One per job end; alert on `status!="COMPLETED"` |
| `pas_extract_records_total{job,file}` | Records written; alert on 0 for `policy_extract.dat` on a business day |
| `pas_transfer_deliveries_total{destination,outcome}` | Final delivery result |
| `pas_transfer_attempt_failures_total{destination}` | Individual failed attempts (retried) |
| `pas_transfer_duration_seconds{destination}` | Delivery time |
| Logger `pas.batch.alert` (ERROR `BATCH ALERT …`) | Failed job, or a scheduled launch that could not start |

Notifiers: every `BatchAlertNotifier` bean is called on failure; `LoggingBatchAlertNotifier` is
always on. Add a PagerDuty/Slack/e-mail notifier as a bean, no job changes needed.

## Manual run / rerun

```bash
java -jar pas-batch.jar --spring.batch.job.enabled=true \
  --spring.batch.job.name=dailyExtractJob businessDate=2026-10-16 launchedAt=$(date +%s)
```

Use `monthlyExposureJob` for Actuarial. With `PAS_TRANSFER_ENABLED=false` the files stay under
`PAS_EXTRACT_OUTPUT_DIR` for inspection.

## Failure handling

| Symptom | Action |
| --- | --- |
| `Server key did not validate` | Host key changed or missing: confirm the new fingerprint with the consumer out of band, update the `known_hosts` secret. Never disable verification |
| `No more authentication methods available` | Our public key is not authorised on the consumer server, or the wrong key is mounted |
| `remote SHA-256 mismatch` / size mismatch | Storage or network problem on the consumer side; the `.part` is left, no `.done` is written. Rerun after the consumer confirms |
| Extract step failed | Database issue; previous day's local file is untouched. Fix, then rerun the same `businessDate` |

## Cut-over

| Consumer | Feed | Cut-over date | Parallel run | Sign-off |
| --- | --- | --- | --- | --- |
| Claims (`CLAIMSRV.ACME.LOCAL`) | `policy_extract.dat`, `coverage_extract.dat` | Pending: Claims | ≥ 5 consecutive business days, byte compare against the mainframe file | Pending: Claims |
| Actuarial (`ACTUARIAL.ACME.LOCAL`) | `exposure_monthly_<YYYYMMDD>.csv` | Pending: Actuarial | ≥ 1 month-end, byte compare | Pending: Actuarial |

Steps: provision SFTP accounts, keys and host-key pins (Pending: Security); parallel run with
`PAS_SCHEDULE_ENABLED=true` delivering to a staging folder; consumer confirms ingest; switch the
remote directory to production; the mainframe team removes the FTP steps from `DAILYEXT`/`MONEXP`
and the CA-7 schedule (Pending: Mainframe team). The repo `jcl/` is left untouched until then.
