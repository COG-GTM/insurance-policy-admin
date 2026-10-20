<!-- Recorded from `make rollback-rehearsal` (pas-platform/dev/migration/rollback-rehearsal.sh) on the AB-529 dev VM. -->
# Rollback rehearsal — LOCAL REHEARSAL ONLY

- When: 20261020T163558Z (UTC) · host: devin-box · result: PASS
- Environment: local Docker Compose; DB2 is a PostgreSQL stand-in (`acmeins_src`); the gateway is a local route-flag file.
- Not covered: real gateway, real DB2 / CDC, production data volumes, reverse replication tooling.
  Production rehearsal is **Pending: Mainframe/Platform/Ops**.

| Step | Result | Evidence |
|---|---|---|
| 0. baseline: bulk load + delta + reconcile | PASS | pas.* matches the DB2 stand-in |
| 1. route flags -> legacy | PASS | all capabilities legacy (local file; gateway config Pending: Platform) |
| 2. pause DB2 -> pas sync | PASS | migration_sync_control.paused = true |
| 3. DB2 takes writes; paused sync applies nothing | PASS | watermarks unchanged (22/2026-10-20 16:35:33.618197) |
| 4. reverse-delta check (pas ahead of DB2) | PASS | 0 rows to replicate back to DB2 |
| 5. inside rollback window | PASS | 6s <= 60m (window value Pending: Ops) |
| 6. re-entry: resume sync, delta, reconcile | PASS | reconciliation PASS after catching up the DB2-side write |

Re-run with `PAS_POLICY_HTTP_PORT=18080 make rollback-rehearsal` if host port 8080 is taken. Steps and the production procedure: [data-migration.md](data-migration.md#rollback).
