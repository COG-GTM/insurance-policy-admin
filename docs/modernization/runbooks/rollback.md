# Runbook: roll PAS back to CICS / DB2 (AB-530)

The tested way back from [cutover.md](cutover.md) to CICS, DB2, CA-7 and `java-facade`. It reuses
the AB-529 rollback design ([data-migration.md](../data-migration.md#rollback)) and the route flags
of ADR 0001 D7.

| | |
|---|---|
| Rollback window | proposed one full billing cycle after the flip (ADR 0001 open question 5). **Pending: Business owner + ARB** |
| Time to cut back | under 60 min (`ROLLBACK_WINDOW_MINUTES`). **Pending: Ops** to agree |
| Tested | Locally only: `make rollback-rehearsal` (AB-529, before the flip) and `make cutover-rehearsal` (AB-530, after the flip, with writes on the new platform). Results: [rehearsal-results-local.md](rehearsal-results-local.md). **Production / pre-prod rehearsal: Pending: Mainframe/Platform/Ops** |
| Decision | Cutover lead, with the Business owner informed (rollback triggers in [cutover.md §8](cutover.md#8-go--no-go-criteria)) |

## Which case are you in?

| Case | When | Data to carry back to DB2 |
|---|---|---|
| A. Before the system-of-record flip | any capability still `shadow` (parallel run), or the cutover was abandoned before step 7 | none: only the DB2 sync wrote `pas`. Reverse-delta check must show 0 rows |
| B. After the flip, inside the rollback window | capabilities `new`, pas is system of record, reverse replication running | every row written by the new platform since the flip. Reverse replication must have caught up before CICS takes writes |
| C. After the window closed / decommission started | CICS transactions or CA-7 jobs already removed | not covered: rollback is no longer possible. Restore from the archive ([decommission.md](../decommission.md)) is a disaster-recovery exercise |

## Steps (case B; case A skips R4)

| Step | Action | Command / config | Check | Owner |
|---|---|---|---|---|
| R1 | Declare rollback, start the clock, freeze writes on the new platform (route writes to maintenance) | change record | No `pas` writes after `ROLLBACK_TS` | Cutover lead |
| R2 | **Route flags → `legacy`** for every capability in one reviewed change | `python3 scripts/cutover/route_flags.py set legacy --all`; gateway applies it | `route_flags.py validate` green; gateway routes on legacy upstreams; facade serves `/api/v1` again | Platform team |
| R3 | **Batch back to CA-7**: disable pas-batch schedules, release CA-7 holds | `PAS_BATCH_PREMIUM_CRON=-`, `PAS_SCHEDULE_ENABLED=false`, `PAS_BATCH_PREMIUM_WRITE_TARGET=shadow`; release PAS0050/PAS0100/PAS0200 | No `pas-batch` job starts; CA-7 shows the jobs scheduled | Ops, Mainframe ops lead |
| R4 | **Reverse replication catch-up** PostgreSQL → DB2 | mainframe tooling (ADR 0001 D3 step 5; not built in this repo) | Reverse-delta = 0: no `pas` row that DB2 lacks or holds differently (the rehearsal's `pas_ahead` query) | Mainframe ops lead, DBA lead |
| R5 | **Move DB2 `POLICY_SEQ`** above `pas.policy_seq` so POLNEW cannot reuse a number issued by the new platform | `ALTER SEQUENCE ACMEINS.POLICY_SEQ RESTART WITH <next pas.policy_seq>` (DB2) | next DB2 value ≥ next `pas.policy_seq` | DBA lead |
| R6 | **Re-enable CICS** write transactions PNEW, PRWL, PEND, PUWR | CEMT … ENABLED | Test transaction on each | Mainframe ops lead |
| R7 | **Resume DB2 → pas sync** and reconcile with DB2 as the reference | `--pas.migration.cli=resume-sync`, then `--pas.migration.cli=delta-sync,reconcile`; `PAS_MIGRATION_SCHEDULE_ENABLED=true` | exit 0, reconciliation PASS, zero unexplained | DBA lead |
| R8 | **Smoke the legacy side**: PQRY and facade `GET /api/v1/policies/{n}`; one PNEW/PUWR test transaction; next CA-7 run delivers the FTP feeds | `compat/api/check_api.py` against the facade; CICS test scripts | Responses match `compat/api/snapshots/policies-v1.json`; feeds received | Mainframe ops lead, consumer leads |
| R9 | Stop the clock; communicate (cutover.md §7 "On rollback") | — | Elapsed ≤ window | Cutover lead |
| R10 | Re-entry: fix, re-run the parallel run until clean, new go/no-go | [parallel-run.md](../parallel-run.md) | — | Cutover lead |

## Per-capability notes

| Capability | Path back | Data concern |
|---|---|---|
| `inquiry`, `broker-read` | flag → `legacy`; facade and linked server serve again | none (read only). Broker Portal rollback steps: **pending AB-528 merge** |
| `new-business`, `underwriting` | flag → `legacy`; PNEW/PUWR re-enabled | policies, coverages and decisions created since the flip → R4; `POLICY_SEQ` → R5. Decisions keep their DB2 key `(policy_number, decision_date)` (two same-day decisions collide in DB2, Q-20) |
| `renewal`, `endorsement` | flag → `legacy`; PRWL/PEND re-enabled | renewed terms and ENDORSEMENTS rows since the flip → R4 (a renewal across 29 Feb carries the Q-01 fix, which POLRNW would not have produced; flag such policies to the PO). `pas.policy_audit` rows have no DB2 table: keep them in PostgreSQL for the audit trail |
| `premium-batch` | cron off, PAS0050 released | PREMIUMS rows written by `premiumRecalculationJob` → R4. PREMBAT fails with -803 on rows that already exist for the term (Q-22): hold PAS0050 until R4 is done |
| `claims-extract`, `actuarial-exposure` | schedule off, PAS0100/PAS0200 released | files already delivered over SFTP stay with the consumer; tell Claims/Actuarial which delivery path the next file comes from |
| `document-events` | flag → `legacy` | events already published on Kafka stay published; the FileNet bridge must not process them twice. **Pending AB-528 merge** |

## Local rehearsal

| Command | Covers | Doesn't cover |
|---|---|---|
| `make rollback-rehearsal` (AB-529) | case A: flags → legacy, pause sync, DB2 takes writes, reverse-delta = 0, window, re-entry with reconciliation PASS | writes on the new platform |
| `make cutover-rehearsal` (AB-530) | case B: real writes on the new platform (new business, underwriting, premium run), flags → legacy, reverse replication by a SQL stand-in that upserts every row DB2 lacks or holds differently, DB2 `POLICY_SEQ` moved, resume sync, reconciliation PASS, window | real gateway, CICS/CA-7, real DB2/CDC, the reverse replication product, production volumes |

## Sign-offs

| Item | Status |
|---|---|
| This runbook | Pending: Ops; Pending: Mainframe team |
| Rollback window and time to cut back | Pending: Business owner + ARB; Pending: Ops |
| Pre-prod rollback rehearsal with reverse replication | Pending: Mainframe/Platform/Ops |
