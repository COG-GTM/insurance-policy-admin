# Runbook: PAS cutover to pas-platform (AB-530)

Moves every PAS capability from CICS / DB2 / CA-7 / `java-facade` to `pas-policy-service` and
`pas-batch` in one system-of-record flip ([ADR 0001](../../adr/0001-pas-target-architecture.md)
D3 step 5 and D7). The way back is in [rollback.md](rollback.md). What gets switched off afterwards
is in [decommission.md](../decommission.md).

| | |
|---|---|
| Status | **Ready for rehearsal only.** Rehearsed locally on Compose ([rehearsal-results-local.md](rehearsal-results-local.md)). The production cutover has not happened. |
| Cutover date / freeze window | **Pending: Business owner + Ops** (proposal below) |
| Blocked by | AB-528 (document events / FileNet bridge, Broker Portal) is not merged yet; its steps are marked *pending AB-528 merge* |
| Sign-off of this runbook | Pending: Ops. Pending: Mainframe team. Pending: Business owner. |

Local rehearsal of the whole runbook, including the rollback: `make cutover-rehearsal`
(`pas-platform/dev/migration/cutover-rehearsal.sh`; CI job `cutover / rehearsal (local)`).

## 1. Roles

Named people go in the change record. This repo only names roles.

| Role | Responsibility in the window | Go/no-go vote |
|---|---|---|
| Cutover lead (PAS Platform engineering manager) | Runs this runbook, keeps the timeline, calls go/no-go and rollback | yes (chair) |
| Business owner (PAS) | Accepts business risk, approves the freeze and go-live | yes |
| Mainframe ops lead | CICS freeze, CA-7 holds, DB2 final state, reverse replication | yes |
| DBA lead (DB2 + PostgreSQL) | Final sync, reconciliation, `POLICY_SEQ`, backups / PITR mark | yes |
| Platform team (gateway) | Applies the route-flag change (`pas-platform/routing/route-flags.json`) | yes |
| Security | Smoke-test IdP client, SFTP keys and host keys, credential revocation later | yes |
| PAS on-call (Ops) | Watches dashboards and alerts, then hypercare | yes |
| Consumer leads: Claims, Actuarial, Broker Portal, FileNet/ECM | Confirm their feed/integration after the switch | for their capability |
| Change / comms manager | Sends the communications in section 7 | no |

## 2. Preconditions (all must be true before the go/no-go call at T-1 day)

| # | Criterion | Evidence | Owner |
|---|---|---|---|
| P1 | Every capability meets the ADR 0001 D7 "moved" rule: parity green, parallel run clean, consumer sign-off, rollback rehearsed, NFRs met, secure and operable | per-capability ticket + flag PR | Cutover lead |
| P2 | Full billing cycle of parallel run with zero unexplained differences ([parallel-run.md](../parallel-run.md)); `consecutivePassingDays` covers the cycle | `/actuator/migration`, `pas.migration_check_runs` | DBA lead, Business owner |
| P3 | `python3 scripts/cutover/route_flags.py validate --production` passes on the change PR with every capability set to `new`, so no capability is still `pendingMerge` (today: document-events, broker-read → AB-528) | CI on the flag PR | Cutover lead |
| P4 | Production rollback rehearsal in pre-prod within the window, including reverse replication | rehearsal record | Mainframe ops lead, Ops |
| P5 | Reverse replication PostgreSQL → DB2 built and tested (ADR 0001 D3 step 5). **Not built in this repo**; the local rehearsal uses a SQL stand-in | pre-prod test | Mainframe team |
| P6 | Real claims adapter deployed: `pas.claims.adapter` is not `unavailable`, or UW returns 503 (OQ, AB-525) | config review | Claims team, PAS Platform |
| P7 | Replacement channel for CICS 3270 users is live (ADR 0001 open question 3) | business readiness | Business owner |
| P8 | SFTP accounts, keys and host-key pins for Claims and Actuarial in the secrets store; production `PAS_*_SFTP_*` values set | Security checklist | Security |
| P9 | Smoke-test identities: IdP client for `scripts/cutover/smoke-tests.sh` (`PAS_SMOKE_TOKEN_CMD`), smoke agent/branch/policyholder (`PAS_SMOKE_AGENT`, `PAS_SMOKE_BRANCH`, `PAS_SMOKE_HOLDER`) and how the smoke policy is cancelled afterwards | Security, Business owner | Security |
| P10 | Spring Boot 3.5.x upgrade landed (ADR 0001 follow-up); Trivy/CodeQL clean or exceptions recorded | CI | PAS Platform |
| P11 | Dashboards, alerts and on-call rotation in place ([operations-handover.md](operations-handover.md)) | Ops acceptance | Ops |
| P12 | `make compat`, `make parity`, `./mvnw verify` green on the release being deployed | CI run link | PAS Platform |

## 3. Freeze window

**Proposal (Pending: Business owner + Ops):** Saturday 06:00–14:00 America/New_York. No CA-7 PAS
job runs in that window (PAS0050 premium 01:00 daily, PAS0100 extract 23:00 daily, PAS0200 exposure
02:00 on the 1st). Do not choose a window that contains the 1st of the month or a month-end.

During the freeze no policy changes are made on either side:
- CICS: PNEW, PRWL, PEND, PUWR disabled for users (CEMT SET TRANSACTION … DISABLED). PQRY stays up
  (read only) until step 4. Owner: Mainframe ops lead.
- CA-7: PAS0050, PAS0100, PAS0200 on hold. Owner: Mainframe ops lead.
- pas-platform: write routes stay `legacy`, so nothing writes `pas` except the DB2 sync.

## 4. Steps

Times are relative to the start of the freeze (T0). Each step lists its local rehearsal equivalent.

| Step | Time | Action | Command / config | Check | Owner |
|---|---|---|---|---|---|
| 1 | T0 | Open the freeze (section 3). Record `FREEZE_TS` (DB2 `CURRENT TIMESTAMP`) | CICS/CA-7 commands in the change record | No DB2 write with `LAST_UPDATED > FREEZE_TS` from here on | Mainframe ops lead |
| 2 | T0+10m | Back up / mark: DB2 image copy of ACMEINS; PostgreSQL PITR restore point | DBA procedures | Backup IDs in the change record | DBA lead |
| 3 | T0+20m | **Final delta sync + reconciliation** (AB-529) | `java -jar pas-batch.jar --spring.main.web-application-type=none --pas.migration.cli=delta-sync,reconcile --pas.migration.business-date=<date>` | Exit 0; `reports/reconciliation/latest.md` PASS; zero unexplained; row counts and financial totals match | DBA lead |
| 4 | T0+40m | **Move `POLICY_SEQ`** so new business never reuses a DB2 number (Q-27) | `SELECT setval('pas.policy_seq', GREATEST(<next pas value>, <DB2 POLICY_SEQ NEXTVAL>), false);` | next `pas.policy_seq` ≥ next DB2 `POLICY_SEQ` | DBA lead |
| 5 | T0+45m | **Stop DB2 → pas sync**. pas becomes system of record | `--pas.migration.cli=pause-sync`; then `PAS_MIGRATION_SCHEDULE_ENABLED=false` | `pas.migration_sync_control.paused = true` for `DB2_TO_PAS` | DBA lead |
| 6 | T0+50m | Start reverse replication PostgreSQL → DB2 for the rollback window | Mainframe tooling (P5) | First change replicated to DB2 | Mainframe ops lead |
| 7 | T0+60m | **Flip route flags** (all capabilities in one reviewed change, section 5) | `python3 scripts/cutover/route_flags.py set --production new --all` on the change PR, merged; the gateway applies it | `route_flags.py validate --production` green; gateway shows every route on the new upstream | Platform team |
| 8 | T0+60m | **Switch batch ownership** (section 5 table): enable pas-batch schedules, hold CA-7 jobs permanently | env changes below, rollout of `pas-batch` | `pas-batch` readiness up; CA-7 jobs on hold | Ops, Mainframe ops lead |
| 9 | T0+75m | **Smoke tests** for every capability (section 6) | `scripts/cutover/smoke-tests.sh` | All PASS, none FAIL/PENDING/SKIP (premium/extract may be SKIP and checked on their first scheduled run) | PAS on-call |
| 10 | T0+90m | **Go/no-go to open the doors** (section 8). Rollback is still a flag flip | — | Votes recorded | Cutover lead |
| 11 | T0+95m | End freeze: enable users on the new channel; CICS write transactions stay disabled | — | First real transaction observed | Business owner |
| 12 | T0+2h → +14d | Hypercare (section 9) | — | No Sev1/Sev2 | PAS on-call |

## 5. Routing switch per capability

Route flags are the D7 states in `pas-platform/routing/route-flags.json` (`legacy` → `shadow` →
`new` → `retired`), changed only by a reviewed PR and validated by `scripts/cutover/route_flags.py`
(write capabilities must flip together). How the routing layer reads the file is **Pending:
Platform team** (gateway vs `pas-edge`, ADR 0001 follow-up). The service configuration that goes
with each flip:

| Capability (flag key) | Legacy | New | Service configuration at the flip | Ticket |
|---|---|---|---|---|
| `inquiry` | POLQRY (PQRY) + `java-facade` `/api/v1` | `pas-policy-service` `GET /api/v1/policies/**` | gateway upstream; facade routes per consumer `pas.inquiry.route.<consumer>=new` ([facade-retirement.md](../facade-retirement.md) §3); `PAS_OIDC_ISSUER_URI`, `PAS_OIDC_AUDIENCE` = production IdP | AB-524 |
| `broker-read` | Broker Portal linked server → DB2 | `pas-policy-service` with agent/branch entitlements | Broker Portal client in the IdP (`agent_code`/`branch_code` claims). **Broker Portal integration steps: pending AB-528 merge** | AB-524 / AB-528 |
| `new-business` | POLNEW (PNEW) | `POST /api/v1/policies` (`policy:write`, `Idempotency-Key`) | `PAS_OUTBOX_RELAY_ENABLED=true`; `PAS_BUSINESS_TIME_ZONE` confirmed; step 4 done | AB-525 |
| `underwriting` | UNDWRT (PUWR) | `POST /api/v1/policies/{n}/underwriting-decisions` (`underwriting:decide`) | `PAS_CLAIMS_ADAPTER` = the real Claims adapter (never `unavailable` or `stub` in production) | AB-525 |
| `renewal` | POLRNW (PRWL) | `POST /api/v1/policies/{n}/renewals` (`policy:write`) | `pas.renewal.uplift-pct` / `PAS_RENEWAL_UPLIFT_PCT` (5, Q-05), `pas.renewal.rate-cap-pct` / `PAS_RENEWAL_RATE_CAP_PCT` (15, Q-04), `pas.renewal.max-open-claims` / `PAS_RENEWAL_MAX_OPEN_CLAIMS` (3, Q-02 fix); event `PolicyRenewed`; audit row in `pas.policy_audit` (V8); needs the real Claims adapter (`PAS_CLAIMS_ADAPTER`) | AB-526 |
| `endorsement` | POLEND (PEND) | `POST /api/v1/policies/{n}/endorsements` (`policy:write`, body `endorsementType`) | event `PolicyEndorsed`; `pas.policy_audit` (V8) replaces the ENDORSEMENT-AUDIT TS queue | AB-526 |
| `premium-batch` | PREMIUM-BATCH / PREMBAT, CA-7 PAS0050 01:00 | `premiumRecalculationJob` | `PAS_BATCH_PREMIUM_WRITE_TARGET=pas` (was `shadow` in the parallel run); `PAS_BATCH_PREMIUM_CRON="0 0 1 * * *"`; `PAS_BATCH_PREMIUM_ZONE=America/New_York`; PAS0050 held | AB-523 |
| `claims-extract` | DAILY-EXTRACT (POLEXT), CA-7 PAS0100 23:00 | `dailyExtractJob` + SFTP | `PAS_SCHEDULE_ENABLED=true`, `PAS_TRANSFER_ENABLED=true`, `PAS_CLAIMS_SFTP_*`, `PAS_SFTP_KNOWN_HOSTS_FILE`, `PAS_CLAIMS_SFTP_REMOTE_DIR` = production folder; PAS0100 held | AB-527 |
| `actuarial-exposure` | MONTHLY-EXPOSURE (MONEXP), CA-7 PAS0200 02:00 on the 1st | `monthlyExposureJob` + SFTP | same as above with `PAS_ACTUARIAL_SFTP_*`; PAS0200 held | AB-527 |
| `document-events` | CICS TDQ/TSQ → MQ → FileNet | `pas.outbox` → Kafka `pas.policy.events.v1` / `pas.underwriting.events.v1` → FileNet bridge | `PAS_OUTBOX_RELAY_ENABLED=true`; `PAS_KAFKA_BOOTSTRAP_SERVERS`; **FileNet bridge and MQ retirement: pending AB-528 merge** | AB-525 / AB-528 |

Also at the flip, for the DB2 sync jobs: `pause-sync` (step 5) and `PAS_MIGRATION_SCHEDULE_ENABLED=false`.
Keep `PAS_MIGRATION_ENABLED=true` with the source credentials for the rollback window, because the
rollback re-entry (`resume-sync`) needs them.

## 6. Smoke tests

`scripts/cutover/smoke-tests.sh [capability ...]` prints `SMOKE <capability> PASS|FAIL|PENDING|SKIP`
and exits 1 on any FAIL. Production settings: `PAS_POLICY_URL`, `PAS_SMOKE_TOKEN_CMD`,
`PAS_SMOKE_POLICY` (the designated smoke policy: renewal and endorsement change it), `PAS_SMOKE_AGENT`/`_BRANCH`/`_HOLDER`, `PAS_SMOKE_PSQL` (read-only role), and optionally
`PAS_SMOKE_BATCH_CMD`/`PAS_SMOKE_EXTRACT_DIR` (a one-shot `pas-batch` job) and `PAS_SMOKE_KAFKA_CMD`.

| Capability | What the smoke test does | Pass condition |
|---|---|---|
| `inquiry` | `GET /api/v1/policies/{n}` and `/coverages` with a `book:*` token; same call with no token | 200 + same policy number; 401 without a token |
| `broker-read` | same policy with an `agent_code` token for the owning agent, then for another agent | 200, then 404 (never 403) |
| `new-business` | `POST /api/v1/policies` with an `Idempotency-Key` for the smoke policyholder, then GET it | 201, policy readable |
| `underwriting` | `POST /api/v1/policies/{new}/underwriting-decisions` | 201 with a decision code (503 = Claims adapter unavailable) |
| `document-events` | `pas.outbox` rows for the new policy all have `published_at`; record keyed by the policy number on `pas.policy.events.v1` | published within 60 s. FileNet document created: **pending AB-528 merge** |
| `premium-batch` | one-shot `--pas.batch.premium.cli.run=true --pas.batch.premium.cli.run-date=<date>`, then `pas_batch.premium_run_summary` | CLI exit 0; latest row `write_target = PAS`, `outcome = COMPLETED` |
| `claims-extract` | one-shot `dailyExtractJob` with `businessDate=<date>` | exit 0; `policy_extract.dat`, `coverage_extract.dat` non-empty |
| `actuarial-exposure` | one-shot `monthlyExposureJob` for the 1st of the month | exit 0; `exposure_monthly.csv` non-empty |
| `renewal` | `POST /api/v1/policies/{smoke policy}/renewals` | 201 (503 = Claims adapter unavailable) |
| `endorsement` | `POST /api/v1/policies/{smoke policy}/endorsements` with `endorsementType` `ACH` | 201 |

In production, run the batch smoke tests with `PAS_TRANSFER_ENABLED=false` against a scratch
`PAS_EXTRACT_OUTPUT_DIR` so nothing is delivered early. The first scheduled run is then checked
with the extract runbook ([extracts-runbook.md](../extracts-runbook.md)) and the consumer confirms
pickup. Cancel the smoke policy afterwards as agreed in P9.

## 7. Communications plan

| When | Audience | Message | Channel | Sender |
|---|---|---|---|---|
| T-10 business days | CICS users, agents, Broker Portal, Claims, Actuarial, FileNet/ECM, service desk | Date, freeze window, what changes for each group (new channel, bearer tokens, SFTP), support contacts | e-mail + intranet | Change / comms manager |
| T-2 days | same | Reminder, go/no-go time | e-mail | Change / comms manager |
| T-1 day | Cutover team | Go/no-go outcome (section 8) | bridge + change record | Cutover lead |
| T0 | All users | Freeze started; CICS write transactions disabled | e-mail + CICS logon banner | Mainframe ops lead |
| Step 10 | Cutover team, business owner | Go/no-go to open the doors; rollback still possible | bridge | Cutover lead |
| Step 11 | All users | Live on the new platform; how to report problems | e-mail + intranet | Change / comms manager |
| Daily during hypercare | Business owner, consumers, Ops | Incidents, reconciliation status, open issues | status e-mail | PAS on-call |
| On rollback | All | Back on CICS/DB2; what to do with in-flight work | e-mail + banner | Cutover lead |
| Day 14 | Business owner, ARB, Ops | Hypercare exit report, decommission start ([decommission.md](../decommission.md)) | e-mail | Cutover lead |

## 8. Go / no-go criteria

| Gate | Criterion | Decided by |
|---|---|---|
| T-1 day | P1–P12 all true, each with evidence linked in the change record | All voting roles; chair: Cutover lead |
| Step 3 | Final reconciliation PASS with zero unexplained differences | DBA lead |
| Step 9 | Every smoke test PASS (no FAIL, no PENDING; SKIP only for batch capabilities, checked on their first scheduled run) | PAS on-call, Cutover lead |
| Step 10 | No Sev1/Sev2 open; error rate and latency inside the agreed SLO on the dashboards; reverse replication running | Cutover lead, Business owner, Mainframe ops lead |
| Any time before day 14 | **Rollback trigger**: Sev1, a data-integrity defect, a consumer unable to process its feed, or reconciliation differences that can't be explained within 4 h | Cutover lead (Business owner informed) → [rollback.md](rollback.md) |

## 9. Hypercare (2 weeks)

- PAS on-call plus a developer from PAS Platform on the rota; mainframe ops on standby for rollback.
- Daily: premium run summary, extract deliveries and consumer receipts, outbox backlog age,
  error rate and latency, reverse replication lag.
- Acceptance criterion "no Sev1/Sev2 incidents in the first 2 weeks": **Pending: Ops** (tracked in
  the incident tool, not in this repo).
- At day 14 with no rollback, start [decommission.md](../decommission.md).

## Sign-offs

| Item | Status |
|---|---|
| This runbook | Pending: Ops; Pending: Mainframe team; Pending: Business owner |
| Freeze window and date | Pending: Business owner + Ops |
| Production cutover executed | Pending: Cutover lead |
| No Sev1/Sev2 in the first 2 weeks | Pending: Ops |
