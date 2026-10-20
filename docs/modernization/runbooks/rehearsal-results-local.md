<!-- Recorded on the AB-530 dev VM from `make rollback-rehearsal` (AB-529 script) and `make cutover-rehearsal` (AB-530). -->
# Cutover and rollback rehearsals — LOCAL REHEARSAL ONLY

Both rehearsals ran on local Docker Compose with a PostgreSQL schema (`acmeins_src`) standing in for
DB2, a local copy of the route-flag file standing in for the gateway, and (cutover rehearsal) a SQL
stand-in for reverse replication. They show the runbook steps and tooling work end to end. They are
**not** evidence of a production or pre-prod rehearsal: **Pending: Mainframe/Platform/Ops**.

CI re-runs the cutover rehearsal on every PR (`cutover / rehearsal (local)`, result in the job
summary). Re-run locally with `PAS_POLICY_HTTP_PORT=18080 make cutover-rehearsal` if host port 8080 is taken.

## 1. Rollback rehearsal before the flip (AB-529 script, case A)

Result file (verbatim):

- When: 20261020T174048Z (UTC) · host: devin-box · result: PASS
- Environment: local Docker Compose; DB2 is a PostgreSQL stand-in (`acmeins_src`); the gateway is a local route-flag file.
- Not covered: real gateway, real DB2 / CDC, production data volumes, reverse replication tooling.
  Production rehearsal is **Pending: Mainframe/Platform/Ops**.

| Step | Result | Evidence |
|---|---|---|
| 0. baseline: bulk load + delta + reconcile | PASS | pas.* matches the DB2 stand-in |
| 1. route flags -> legacy | PASS | all capabilities legacy (local file; gateway config Pending: Platform) |
| 2. pause DB2 -> pas sync | PASS | migration_sync_control.paused = true |
| 3. DB2 takes writes; paused sync applies nothing | PASS | watermarks unchanged (11/2026-10-20 17:41:40.824266) |
| 4. reverse-delta check (pas ahead of DB2) | PASS | 0 rows to replicate back to DB2 |
| 5. inside rollback window | PASS | 7s <= 60m (window value Pending: Ops) |
| 6. re-entry: resume sync, delta, reconcile | PASS | reconciliation PASS after catching up the DB2-side write |

## 2. Cutover + rollback rehearsal after the flip (AB-530, case B)

Result file (verbatim):

- When: 20261020T174805Z (UTC) · business date 2026-10-20 · host: devin-box · result: PASS
- Environment: local Docker Compose; DB2 is a PostgreSQL stand-in (`acmeins_src`); the gateway is a
  local copy of `pas-platform/routing/route-flags.json`; reverse replication is a SQL stand-in.
- Not covered: real gateway, CICS/CA-7, real DB2/CDC, reverse replication tooling, production volumes,
  SFTP delivery. Production cutover and rollback: **Pending: Mainframe/Platform/Ops**.

| Step | Result | Evidence |
|---|---|---|
| 0. pre-cutover: bulk load + delta + reconcile, flags shadow | PASS | pas.* matches the DB2 stand-in |
| 1. freeze window opened | PASS | CICS/JCL write freeze stand-in at 2026-10-20 17:48:34.954567 (last pre-freeze write: POL-00000002) |
| 2. final delta + reconcile | PASS | reconciliation PASS; pre-freeze write synced; 0 DB2 writes after freeze |
| 3. pas.policy_seq >= DB2 POLICY_SEQ | PASS | next pas 1000000, next DB2 1000000 |
| 4. pause DB2 -> pas sync (pas is system of record) | PASS | migration_sync_control.paused = true |
| 5. route flags -> new | PASS | new: inquiry new-business underwriting premium-batch claims-extract actuarial-exposure |
| 6. smoke tests | PASS | 8 PASS, 2 PENDING (production rules block go-live until AB-526/AB-528 merge (expected); details below) |
| 7. post-cutover: pas ahead of DB2 | PASS | 7 rows written by the new platform since the flip |
| 8. rollback: route flags -> legacy | PASS | all capabilities legacy; CICS/JCL authoritative |
| 9. reverse replication (local stand-in) + DB2 POLICY_SEQ | PASS | 7 rows carried back to DB2; 0 left; DB2 POLICY_SEQ next 1000050 |
| 10. resume sync, delta + reconcile | PASS | reconciliation PASS with DB2 as reference |
| 11. rollback inside window | PASS | 9s <= 60m (window Pending: Ops) |

Smoke tests (`scripts/cutover/smoke-tests.sh`):

```text
SMOKE inquiry PASS GET policy + coverages 200 (book:*), no token 401
SMOKE broker-read PASS agent AG1001 -> 200, agent AG9999 -> 404 (entitlements; Broker Portal side pending AB-528 merge)
SMOKE new-business PASS POST /api/v1/policies 201 -> POL001000000, readable by AG1001
SMOKE underwriting PASS POST underwriting-decisions 201 -> AP for POL001000000
SMOKE renewal PENDING pending AB-526 merge (renewal API not on the integration branch)
SMOKE endorsement PENDING pending AB-526 merge (endorsement API not on the integration branch)
SMOKE premium-batch PASS premiumRecalculationJob 2026-10-20: PAS COMPLETED read=3 updated=3
SMOKE claims-extract PASS dailyExtractJob 2026-10-20 COMPLETED: policy_extract.dat coverage_extract.dat
SMOKE actuarial-exposure PASS monthlyExposureJob 2026-10-01 COMPLETED: exposure_monthly.csv
SMOKE document-events PASS outbox 2/2 published for POL001000000; PolicyCreated on pas.policy.events.v1 (FileNet bridge pending AB-528 merge)
```

Notes:
- Renewal and endorsement are PENDING because AB-526 has not merged; the production route-flag
  rules (`route_flags.py validate --production`) therefore refuse a go-live flip, as intended.
- Step 7/9 row counts: the smoke tests created one policy with two coverages and one underwriting
  decision, and the premium run wrote PREMIUMS rows; all of them were carried back to the DB2
  stand-in before reconciliation passed with DB2 as the reference.
