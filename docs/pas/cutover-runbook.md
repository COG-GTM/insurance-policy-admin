# PAS cutover runbook (AB-530)

Switches every PAS capability from COBOL/CICS/DB2 to `pas-policy-service`, `pas-batch` and PostgreSQL
ACMEINS in one freeze window, with a tested way back ([rollback-runbook.md](rollback-runbook.md)).
The machine-readable plan is [`cutover.json`](cutover.json); `make cutover` validates it and prints
the go/no-go decision. **Nothing in this repository switches production**: every step is run by its
owner under a change record, and the legacy trees `cobol/`, `jcl/`, `java-facade/` and `sql/` stay
unchanged until the separately gated [mainframe decommission](mainframe-decommission.md).

## Owners

Named people are not known to this repository; each role below must have a name before go/no-go 1
(`make cutover` stays NO-GO while any `owners[].name` is empty, FU-530-1).

| Role | Responsibility | Name |
|---|---|---|
| Cutover lead (release manager) | routing state of every capability, go/no-go calls, rollback decision | pending |
| Mainframe team | CICS transactions, CA-7 holds and releases, DB2 freeze | pending |
| DBA | final sync, reconciliation, sequence alignment, reverse sync | pending |
| Platform SRE | deployments, gateway routes, platform scheduler, alerts | pending |
| Product owner | business acceptance, SM-03 test policy | pending |
| Security | production configuration approval | pending |
| Claims and Actuarial feed owners | feed readiness and import confirmation | pending |
| Service owner (operations) | hypercare and support after handover ([support-runbook.md](support-runbook.md)) | pending |

## Prerequisites (from earlier tickets)

`make cutover` reads these plans and lists every open item; all must be closed for GO.

| Id | Plan | What must be closed |
|---|---|---|
| P1 | [`parallel-run.json`](parallel-run.json) (AB-529) | billing cycle dates, rollback window, production-sized rollback rehearsal, all sign-offs |
| P2 | [`extracts-cutover.json`](extracts-cutover.json) (AB-527) | Claims/Actuarial parallel runs and sign-offs |
| P3 | [`facade-retirement.json`](facade-retirement.json) (AB-524) | G1 consumer inventory, G2 IdP registration, G4 compat gate repointed |
| P4 | [`messaging-decommission.json`](messaging-decommission.json) (AB-528) | G1-G3: event path verified with FileNet and the underwriting work queue, Broker Portal on the API |

## Freeze window

Proposed: Saturday night after the 01:00 PAS0050 premium run, ending before Monday 06:00 channel
opening, at most 10 hours, outside month end (PAS0200 runs on the 1st) and outside the Claims cut-off.
The dates are agreed with the business and downstream owners (FU-530-2) and recorded in
`freeze_window.start`/`end`; `make cutover` rejects a window longer than `max_hours`.

During the freeze **no PAS write happens on any path**: CICS PNEW, PRWL, PEND and PUWR disabled (PQRY
stays enabled, read-only), CA-7 PAS0050/PAS0100/PAS0200 held, and the gateway answers 503 on API
write routes. This is the single-writer rule of ADR 0001 D7 applied to the whole switch.

## Steps

| Step | When | Action | Owner |
|---|---|---|---|
| CO-01 | T-5 business days | Go/no-go 1: `make cutover` shows only the freeze-time criteria open; freeze notice sent to channels, Claims, Actuarial, Broker Portal, FileNet and underwriting work queue owners | Cutover lead |
| CO-02 | T-1 | Last parallel-run day clean (`make migration-parallel-run`); latest `migrationReconciliation` MATCH; DB2 image copies of ACMEINS and a PostgreSQL base backup; pick the SM-01 smoke policy and note its DB2 premium | DBA |
| CO-03 | Freeze start | Disable CICS write transactions, hold the CA-7 jobs, 503 on API writes | Mainframe team + Platform SRE |
| CO-04 | Final sync | `PAS_BATCH_JOB=migrationDeltaSync` until a run applies zero changes, then `PAS_BATCH_JOB=migrationReconciliation`; any difference is NO-GO | DBA |
| CO-05 | Final sync | Restart the PostgreSQL policy-number sequence above the DB2 high-water mark plus the DB2 cache ([data-migration.md](data-migration.md)) | DBA |
| CO-06 | Switch | `PAS_BATCH_JOB=migrationSystemOfRecord system=PG confirm=YES changedBy=<id> "reason=<change ticket>"`; point `pas-policy-service` and `pas-batch` at PostgreSQL; enable `migrationReverseSync` readiness for the rollback window | DBA + Platform SRE |
| CO-07 | Switch | Route capabilities in order C-11, C-3, C-10, C-4, C-5, C-6, C-7, C-1/C-2, C-8, C-9 (table below); record each `LEGACY` → `NEW` change | Cutover lead |
| CO-08 | Verify | `pas/scripts/cutover-smoke.sh` against production with `PAS_ALERTMANAGER_URL` (SM-01, SM-02, SM-04, SM-07); SM-03 by the product owner; SM-08 event delivery evidence. SM-05/SM-06 are not run inside the freeze: the first scheduled batch runs fall in hypercare (CO-10) | Platform SRE |
| CO-09 | Freeze end | Go/no-go 2: SM-01, SM-02, SM-03, SM-04, SM-07 and SM-08 passed (`requires_smoke`, checked by `make cutover` against the `blocks_reopen` smoke tests) and no open Sev1/Sev2 → reopen writes; otherwise run the rollback runbook before the window closes | Cutover lead |
| CO-10 | +14 days | Hypercare: daily reconciliation and alert review; every Sev1/Sev2 recorded in `hypercare.incidents`; `make cutover-hypercare` decides exit | Service owner |

## Routing switch per capability

| Capability | Legacy | Switch to `NEW` | Smoke | Back to `LEGACY` |
|---|---|---|---|---|
| C-11 System of record | DB2 ACMEINS | `migrationSystemOfRecord system=PG`; datasource to PostgreSQL | SM-01, SM-07 | [rollback-runbook.md](rollback-runbook.md) |
| C-3 Policy inquiry | PQRY, `java-facade` | gateway route `/api/v1/policies/**` to `pas-policy-service` | SM-01, SM-02 | gateway route back to the facade |
| C-10 Events, Broker Portal | TD queue `ACME`, ODBC linked server | outbox + Kafka + MQ bridge; Broker Portal on `GET /api/v1/policies` | SM-04, SM-08 | CICS path writes the TD queue again with C-4..C-7 on `LEGACY` |
| C-4 New business | CICS PNEW | channel on `POST /api/v1/policies`; PNEW disabled | SM-03 | `CEMT SET TRANSACTION(PNEW) ENABLED` |
| C-5 Underwriting | CICS PUWR | channel on the underwriting APIs; PUWR disabled | SM-03 | `CEMT SET TRANSACTION(PUWR) ENABLED` |
| C-6 Renewal | CICS PRWL | channel on `.../renewals`; PRWL disabled | SM-03 | `CEMT SET TRANSACTION(PRWL) ENABLED` |
| C-7 Endorsement | CICS PEND | channel on `.../endorsements`; PEND disabled | SM-03 | `CEMT SET TRANSACTION(PEND) ENABLED` |
| C-1 Rating, C-2 Premium batch | PREMBAT, CA-7 PAS0050 | PAS0050 stays held; `premiumRecalculation` enabled | SM-05 | suspend the job, release PAS0050 |
| C-8 Claims extract | CA-7 PAS0100 | PAS0100 stays held; `claimsDailyExtract` enabled | SM-06 | suspend the job, release PAS0100 |
| C-9 Actuarial feed | CA-7 PAS0200 | PAS0200 stays held; `actuarialMonthlyExposure` enabled | SM-06 | suspend the job, release PAS0200 |

`make cutover` fails if this list drifts from the ADR 0001 D7 capability table.

## Smoke tests

SM-01, SM-02, SM-04 (list endpoint) and SM-07 are automated in
[`pas/scripts/cutover-smoke.sh`](../../pas/scripts/cutover-smoke.sh): read-only, no test data written.
Against the local stack it runs with no arguments (tokens minted by `local-token.sh`); against a
shared environment set `PAS_API_URL`, `PAS_MGMT_URL`, `PAS_READ_JWT` (scope `pas.policy.read`),
`PAS_OPS_JWT` (scope `pas.ops.read`), and optionally `PAS_SMOKE_POLICY`/`PAS_SMOKE_PREMIUM` (the
CO-02 policy and its DB2 `TOTAL_PREMIUM`, compared with `totalPremium` in the inquiry response) and
`PAS_SMOKE_AGENT`. `tools/retirement/test_cutover_smoke.py` (part of `make cutover`) runs the script
against a stub API, including the premium comparison. `PAS_ALERTMANAGER_URL` is mandatory whenever
`PAS_API_URL` is set: outside the local stack the script fails rather than skip the SM-07 alert check. Tokens come from the
enterprise IdP and are passed through the environment, never written to files.

SM-03 (one test policy created, underwritten, endorsed, renewed and then cancelled by the product
owner on a test agent/branch) and SM-08 (for each SM-03 event id: `published_at` set in `pasapp.event_outbox`, not parked or
dead-lettered, and delivered by the bridge to the destination for its event type: FileNet for policy
lifecycle events, the underwriting work queue for underwriting events) are manual and recorded in the change
record before CO-09. SM-05 (first premium run totals) and SM-06 (first extracts received) depend on
the first scheduled batch runs, which fall after the freeze; they are checked in hypercare (CO-10) and
a failure is a rollback trigger. Their results go in `hypercare.checks`, and `make cutover-hypercare`
stays open past day 14 until both have passed (the first monthly actuarial run can fall after day 14). `make cutover` checks that CO-09's `requires_smoke` is exactly the
smoke tests marked `blocks_reopen`.

## Go/no-go criteria

| Id | Criterion | Owner | Status |
|---|---|---|---|
| GN-01 | Prerequisites P1-P4 closed | Cutover lead | pending |
| GN-02 | Final reconciliation MATCH on all 7 tables | DBA | pending |
| GN-03 | Rollback rehearsed end to end on a production-sized copy within the window | DBA + Mainframe team | pending |
| GN-04 | Automated smoke tests pass in pre-production with the production configuration | Platform SRE | pending |
| GN-05 | Dashboards, alerts, on-call rota live; support runbook accepted | Service owner | pending |
| GN-06 | Downstream owners confirm readiness and the window | Claims and Actuarial feed owners | pending |
| GN-07 | Security approval of the production configuration | Security | pending |
| GN-08 | Product owner acceptance and ARB approval of ADR 0001 | Product owner | pending |

Statuses are changed in `cutover.json` with the evidence link; `make cutover` rejects an approved
criterion without evidence. `python3 tools/retirement/cutover.py --gate` exits 3 while NO-GO.

## Hypercare and the acceptance criterion

AB-530 is accepted when the cutover runs 14 days without a Sev1 or Sev2. Record
`hypercare.cutover_completed_at` at CO-09 and every Sev1/Sev2 from the incident tool (FU-530-3) in
`hypercare.incidents` (`id`, `severity`, `opened`, `summary`). `make cutover-hypercare` prints
`not-started`, `running`, `passed` or `breached`; with `--gate` it exits 0 only when passed. A breach
is reviewed by the cutover lead and the product owner, who decide whether the rollback triggers apply.

## Rollback triggers

Roll back (decision by the cutover lead) when any of these holds inside the rollback window: a Sev1,
or a Sev2 without a fix within 4 hours, caused by the new platform; an unexplained reconciliation
difference older than one business day; a missed or rejected downstream feed; a failed CO-08 smoke
test that cannot be fixed before the end of the freeze.
