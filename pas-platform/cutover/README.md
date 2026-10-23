# Cutover automation

`cutover.sh` and `rollback.sh` are dry-run by default. Pass `--execute` only after
the operational prerequisites and change approvals are complete. Both scripts require
an explicit, reusable `--run-id`; completed step IDs are recorded in
`$PAS_CUTOVER_STATE_DIR` and skipped on a resumed execution. Logs and generated
evidence default to `$HOME/ab530/cutover-state/$PAS_CUTOVER_ENV`.

## Production adapters

Set `PAS_CUTOVER_ENV` to a non-local environment and provide these reviewed hooks:

| Variable | Purpose |
| --- | --- |
| `PAS_CUTOVER_PSQL_CMD` | Execute SQL from stdin against the target database |
| `PAS_CUTOVER_BATCH_CMD` | Invoke a batch job; receives CLI args |
| `PAS_CUTOVER_APPLY_ROUTES_CMD` | Apply route assignments passed as arguments |
| `PAS_CUTOVER_LEGACY_FREEZE_CMD` | Freeze CICS writes and hold CA-7 jobs |
| `PAS_CUTOVER_LEGACY_UNFREEZE_CMD` | Restore legacy write access and scheduling |
| `PAS_CUTOVER_MIGRATION_CONFIG_CMD` | Apply delta/reverse-sync configuration |
| `PAS_SMOKE_JWT` | Short-lived operator JWT for authenticated probes |

All hooks and the JWT are required before execute mode starts. Hook commands are
deployment-specific and must be reviewed and tested by the platform owner. No
production-specific command or credential is embedded here.

```sh
PAS_CUTOVER_ENV=production ./pas-platform/cutover/cutover.sh --run-id CHG-123
PAS_CUTOVER_ENV=production ./pas-platform/cutover/cutover.sh --execute --run-id CHG-123
PAS_CUTOVER_ENV=production ./pas-platform/cutover/rollback.sh --execute --include-reads --run-id CHG-123
```

## Local rehearsal

`make cutover-rehearsal` prepares a local Compose fixture, runs forward migration,
cutover, smoke checks, and rollback. It refuses non-local environment configuration.
Use the separate `make rollback-rehearsal` target to preserve the AB-529 regression
rehearsal. Local host ports default to 18080 and 18081.
When local MQ is not configured, the `local` profile simulates legacy queue sends and
logs only the queue name and payload size; it does not deliver messages to an external
queue.

`flyway-check.sh` compares versioned repository migrations with both Flyway histories.
It fails on failed, unknown, pending, or lower-version migrations; it does not enable
out-of-order migrations or create schema changes. `decommission-legacy-trees.sh`
is separate, dry-run by default, and must only execute in a detached worktree or
approved target clone.
