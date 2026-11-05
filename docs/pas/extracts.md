# Claims and Actuarial extracts (AB-527)

`pas-batch` replaces the JCL jobs DAILY-EXTRACT (CA-7 PAS0100) and MONTHLY-EXPOSURE (CA-7 PAS0200)
and their plain-text transfer steps. The JCL stays frozen; it is retired only through the cut-over
plan in [extracts-cutover.json](extracts-cutover.json).

| Legacy | New job | Files delivered | Schedule (example) |
|---|---|---|---|
| DAILY-EXTRACT STEP010 + STEP030 | `claimsDailyExtract` | `policy_extract.dat` (LRECL 200), `coverage_extract.dat` (LRECL 150) to the Claims endpoint | nightly 23:00 America/New_York |
| MONTHLY-EXPOSURE STEP010 + STEP020 | `actuarialMonthlyExposure` | `exposure_monthly.csv` (max 300 bytes per record) to the Actuarial endpoint | 1st of month 02:00 |

Each data file is followed by `<file>.sha256` (`<sha256>  <file>`, the `sha256sum -c` format).

## Selection logic

The SQL is in `ExtractJdbcRepository` and matches the JCL SYSIN statement for statement:

- **Policy delta:** `POLICY_STATUS IN ('AC','PN') AND LAST_UPDATED >= <window start>`, ordered by
  `POLICY_NUMBER`. The JCL computes the window as `CURRENT DATE - 1 DAY` when the step starts; the
  job uses midnight of `businessDate - 1 day` instead, so late runs and reruns select the same rows
  (Q-527-1). `businessDate` is a job parameter and defaults to today in `pas.extract.business-zone`.
- **Coverages:** every `STATUS = 'AC'` coverage, ordered by `POLICY_NUMBER, SEQUENCE_NUM` (full
  dump, not a delta; Q-31).
- **Exposure:** active policies joined to active coverages, grouped by `POLICY_TYPE, BRANCH_CODE,
  COVERAGE_TYPE, RATING_TERRITORY, CLASS_CODE`, with `COUNT(*) AS POLICY_COUNT` (counts coverage
  rows, Q-34) and the three sums. The JCL orders by the first two columns only; the job orders by all
  five so the output is deterministic. Within `(POLICY_TYPE, BRANCH_CODE)` the mainframe order is
  undefined, so this is the same order the mainframe may produce, not a change of contract.

`ExtractParityIT` reads the SQL verbatim from the frozen JCL, runs it on the same PostgreSQL
database with only `CURRENT DATE` pinned, renders it with the same layout, and requires the
delivered files to be byte-identical for 5 consecutive business dates while the book changes
(new policy, status changes, cancelled coverage, timestamps on the window boundary).

## Record layouts

The JCL only fixes the record lengths; no copybook defines the bytes (Q-527-2, Q-30). `FeedLayouts`
holds the candidate layout until production samples arrive (FU-13) and the consumers confirm it
(FU-14):

- Columns in SELECT-list order; text left-aligned and space padded; dates `YYYY-MM-DD`; decimals and
  integers signed, zero padded, with an explicit decimal point; NULL as spaces; each record padded
  with spaces to the LRECL. Values that do not fit fail the job instead of being truncated.
- CSV: header row, comma separated, RFC 4180 quoting, empty field for NULL.
- ISO-8859-1, CRLF after every record (`pas.extract.charset`, `pas.extract.record-separator`).

| Claims policy (200) | Offset | Width | | Claims coverage (150) | Offset | Width |
|---|---|---|---|---|---|---|
| POLICY_NUMBER | 1 | 12 | | POLICY_NUMBER | 1 | 12 |
| POLICY_TYPE | 13 | 3 | | SEQUENCE_NUM | 13 | 6 |
| POLICY_STATUS | 16 | 2 | | COVERAGE_TYPE | 19 | 4 |
| EFFECTIVE_DATE | 18 | 10 | | DESCRIPTION | 23 | 40 |
| EXPIRY_DATE | 28 | 10 | | COVERAGE_LIMIT | 63 | 15 |
| POLICYHOLDER_ID | 38 | 10 | | DEDUCTIBLE | 78 | 11 |
| AGENT_CODE | 48 | 6 | | PREMIUM | 89 | 13 |
| BRANCH_CODE | 54 | 4 | | EFFECTIVE_DATE | 102 | 10 |
| TOTAL_PREMIUM | 58 | 13 | | EXPIRY_DATE | 112 | 10 |
| DEDUCTIBLE | 71 | 11 | | STATUS | 122 | 2 |
| COVERAGE_LIMIT | 82 | 15 | | filler | 124 | 27 |
| UW_STATUS | 97 | 2 | | | | |
| RISK_SCORE | 99 | 6 | | | | |
| filler | 105 | 96 | | | | |

`FeedLayoutTest` pins the used lengths (104 and 123); any change to `FeedLayouts` that moves a byte
fails it.

## Secure transfer

- SFTP only (`SftpFeedTransfer`, Apache MINA SSHD). A URI with any other scheme, or with user info,
  fails validation before any data is read.
- Public-key authentication only; no password authentication is offered. The private key (and an
  optional passphrase file) and the `known_hosts` file are files mounted read-only from the secrets
  manager; the configuration only holds their paths.
- The server host key must match the pinned `known_hosts` entry; unknown or changed keys are
  rejected (no trust on first use).
- Upload as `<file>.part`, rename into place, check the remote size, read the file back and compare
  its SHA-256 with the staged file. Only then is the delivery confirmed. The manifest is delivered
  last, so its arrival tells the consumer the data file is complete.
- Staged files live in `PAS_EXTRACT_STAGING_DIR` (directory 0700, files 0600) and are deleted after
  confirmed delivery. After a failed delivery they are kept for inspection until the next run of
  the feed rebuilds them; the example CronJobs mount a persistent encrypted volume so they survive
  the pod.
- Replacing a file on the endpoint: with the OpenSSH `posix-rename` extension the upload is renamed
  over the published file atomically. Endpoints without it get a fallback: the previous delivery is
  moved to `<file>.prev`, the upload renamed into place, `.prev` removed; a failed rename restores
  it, and a `.prev` left by an interrupted attempt is restored before cleanup. The published name is
  briefly absent in the fallback, so Security should require posix-rename (or MFT atomic delivery)
  on the production endpoints (FU-15).
- Every delivery writes a receipt to `pasapp.extract_delivery` (Flyway V70): feed, business date,
  file name, record and byte counts, SHA-256, destination without credentials, `CONFIRMED` or
  `FAILED`, attempts, Spring Batch execution id. Receipts hold no business data (DS-07).

## Configuration

| Property | Environment variable | Default |
|---|---|---|
| `pas.extract.staging-dir` | `PAS_EXTRACT_STAGING_DIR` | none (required) |
| `pas.extract.{claims,actuarial}.uri` | `PAS_EXTRACT_{CLAIMS,ACTUARIAL}_SFTP_URI` | none; `sftp://host[:port]/directory` |
| `pas.extract.{claims,actuarial}.username` | `..._SFTP_USERNAME` | none |
| `pas.extract.{claims,actuarial}.private-key-file` | `..._SFTP_KEY_FILE` | none |
| `pas.extract.{claims,actuarial}.private-key-passphrase-file` | `..._SFTP_KEY_PASSPHRASE_FILE` | none (unencrypted key) |
| `pas.extract.{claims,actuarial}.known-hosts-file` | `..._SFTP_KNOWN_HOSTS_FILE` | none |
| `pas.extract.{claims,actuarial}.timeout` | | `PT30S` |
| `pas.extract.retry.max-attempts` / `backoff` | | 3 / `PT1M`, doubling |
| `pas.extract.business-zone` | | `America/New_York` |
| `pas.extract.purge-after-delivery` | | `true` |

A job only validates the destination it delivers to. Missing values fail the launch with a list of
the missing property names, never their values.

## Scheduling, retries and alerting

- Example CronJobs: [pas/deploy/extracts/cronjobs.yaml](../../pas/deploy/extracts/cronjobs.yaml).
  `PAS_BATCH_JOB` selects the job; `concurrencyPolicy: Forbid` replaces the CA-7 exclusivity.
- Job repository: Spring Batch metadata lives in schema `pas_batch` (DS-03), created by Flyway V71
  with the other migrations; batch pods never create tables (`initialize-schema: never`).
- Business date: a scheduled launch passes no arguments; the job incrementer sets `businessDate` to
  today in `pas.extract.business-zone`. Pass `businessDate=YYYY-MM-DD` to run another day.
- Retries: each file is attempted `max-attempts` times with doubling backoff inside a run, then the
  job fails and the process exits non-zero. Relaunching the same `businessDate` (the Kubernetes Job
  retry, or a manual launch) restarts the failed instance: the extract steps run again
  (`allowStartIfComplete`) and rebuild the files from the book, then delivery runs. The policy delta
  selects the same window because it is anchored to `businessDate`.
- Alerting: failed jobs log `pas.extract.alert` at ERROR (job, business date, failure; never
  record content) and exit non-zero. The example rules in
  [pas/deploy/extracts/alerts.yaml](../../pas/deploy/extracts/alerts.yaml) use Kubernetes Job and
  CronJob state and that log line, because the batch pod is gone before any scrape; the Micrometer
  counters `pas.extract.deliveries{feed,outcome}` and `pas.extract.job.failures` are in-process
  only. Who is paged today is FU-12.

## Failure and rerun

1. Check the receipts: `SELECT * FROM pasapp.extract_delivery WHERE business_date = ... ORDER BY id`.
2. Endpoint or key problem: fix the secret or `known_hosts` entry (host-key changes need Security
   approval), then restart the failed execution with the same parameters.
3. Re-sending a completed day (consumer request): launch with `businessDate=YYYY-MM-DD` and an extra
   identifying parameter such as `rerun=2`. The policy delta is reproducible because the window is
   computed from `businessDate`; the coverage dump and exposure reflect the data at run time, as on
   the mainframe.

## Parity against the mainframe

`make extract-parity` runs the tool tests, validates the cut-over register and runs the extract test
suites. During the parallel run, put captured mainframe files and the pas-batch files in one
directory per run (`YYYY-MM-DD/policy_extract.dat` and so on) and run:

```
make extract-parity CAPTURED=/path/to/mainframe CANDIDATE=/path/to/pas-batch
```

Each feed needs 5 consecutive byte-identical runs (`--required`); a missing scheduled run (a day for the Claims feeds, or a weekday with `--claims-calendar weekdays`; the first of each month for exposure) breaks the streak, as does a capture filed under any other date (late or manual replays), and every feed must pass. Exposure rows that differ only in
order within `(POLICY_TYPE, BRANCH_CODE)` are reported as `ORDR` and count only with
`--exposure-order-insensitive`, which needs Actuarial's agreement (Q-34).

## Cut-over and sign-off

Cut-over dates are agreed with each consumer and recorded in
[extracts-cutover.json](extracts-cutover.json); `python3 tools/extracts/extract_parity.py gate`
prints the plan and exits 3 while anything is pending. Nothing in the plan runs on this branch.

| Consumer | Feed | Cut-over date | Status |
|---|---|---|---|
| Claims | policy and coverage extracts | TBD | pending |
| Actuarial | monthly exposure CSV | TBD | pending |

| Sign-off | Scope | Status |
|---|---|---|
| Mainframe team | production samples and unload utility (FU-13) | pending |
| Claims | layout, delta semantics, ingest of the new files, cut-over date (FU-14) | pending |
| Actuarial | CSV layout, POLICY_COUNT, row order, ingest, cut-over date (FU-14) | pending |
| Security | endpoints, keys, host keys, no unencrypted transfers left (FU-15) | pending |
| Mainframe operations | CA-7 hold and retirement of PAS0100/PAS0200 (FU-12) | pending |
| ARB | INT-02, DS-07 | pending |

## Pending outside code

- 5 consecutive byte-identical runs against real mainframe output: needs production samples (FU-13)
  and a parallel run in a production-like environment.
- Claims and Actuarial ingest confirmation and cut-over dates (FU-14).
- SFTP/MFT endpoints, service accounts, key pairs and pinned host keys provisioned by Security
  (FU-15). Until the legacy transfer steps are removed at cut-over, the mainframe still sends the
  files unencrypted; "no unencrypted transfers left" is met at step 4 of the cut-over plan.
