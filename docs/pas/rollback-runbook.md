# PAS rollback runbook: back to CICS/DB2 (AB-530)

Used when a [rollback trigger](cutover-runbook.md#rollback-triggers) fires after cutover and inside the
agreed rollback window (`parallel-run.json` `rollback.window`, FU-529-3). Rollback is a **reverse
sync**, not a restore: writes made on PostgreSQL after cutover are copied back to DB2, so no business
transaction is lost. The database part is AB-529's `migrationReverseSync`
([data-migration.md](data-migration.md#rollback)). Decision owner: cutover lead.

## Steps

| Step | Action | Owner |
|---|---|---|
| RR-01 | Declare rollback in the change record; notify channels and downstream owners | Cutover lead |
| RR-02 | Stop all PostgreSQL writes: gateway 503 on API write routes; suspend `premiumRecalculation`, `claimsDailyExtract`, `actuarialMonthlyExposure` and wait for running pas-batch jobs to finish. Keep `pas-policy-service` running: its `OutboxRelay` and the ops events API are needed in RR-03 | Platform SRE |
| RR-03 | Let the outbox relay and the MQ bridge drain (no rows waiting in `/api/v1/ops/events/outbox/parked`, bridge lag 0) so FileNet and the underwriting work queue have every message written on the new platform; then scale `pas-policy-service` and `pas-batch` to zero before the reverse sync | Platform SRE |
| RR-04 | `PAS_BATCH_JOB=migrationReverseSync confirmRollback=YES`: copies PostgreSQL ACMEINS to DB2, restarts `ACMEINS.POLICY_SEQ` above the PostgreSQL high-water mark, reconciles, and only on a clean reconciliation sets DB2 back as system of record. Same-day underwriting decisions: export, then rerun with `collapseSameDayDecisions=YES` (Q-18) | DBA |
| RR-05 | Confirm `migrationReconciliation` MATCH with DB2 as system of record | DBA |
| RR-06 | Enable CICS PNEW, PRWL, PEND, PUWR (`CEMT SET TRANSACTION(...) ENABLED`); channels back to 3270 | Mainframe team |
| RR-07 | Release CA-7 PAS0050, PAS0100, PAS0200; rerun any cycle the platform jobs did not complete | Mainframe team |
| RR-08 | Gateway route `/api/v1/policies/**` back to the facade if C-3 is rolled back; Broker Portal back on the linked server only if AB-528 G5 has not removed it | Platform SRE |
| RR-09 | Smoke on the legacy path: PQRY on the CO-02 policy, a PNEW test policy by the product owner, first PAS0050 run; record the result and the root cause for a new cutover date | Mainframe team + Product owner |

## Why this path is tested

| Rehearsal | Covers | Status |
|---|---|---|
| `MigrationJobsIT.rollbackRehearsalPointsBackAtTheLegacyDatabaseWithinTheWindow` (CI, `make pas-verify`) | RR-04, RR-05: cutover, new policy and premium change on PostgreSQL, reverse sync, zero differences, sequence above, DB2 system of record | passed (every CI run) |
| `make cutover` | every capability has a rollback, every rollback step has an owner, the plan points at this runbook | passed (every CI run) |
| DB2 for z/OS with a production-sized book, timed against the window (GN-03) | RR-02..RR-09 end to end, including CICS/CA-7 | pending (FU-529-3) |

Partial rollback of one capability before C-11 switches is the D7 routing switch back to `LEGACY`
(the "Back to LEGACY" column of the cutover runbook). Once C-11 has switched, any rollback of a
write capability C-2, C-4..C-9 requires the full runbook above, because only one system of record
may take writes.

After the rollback window closes (mainframe decommission gate G3) this runbook no longer applies; the
DB2 archive is a record, not a rollback target.
