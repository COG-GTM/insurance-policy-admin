# PAS support runbook and operations handover (AB-530)

For the operations team taking over PAS after cutover. Architecture:
[target-state.md](target-state.md) and [ADR 0001](../adr/0001-pas-target-architecture.md).

## Services and jobs

| Component | What it does | Doc |
|---|---|---|
| `pas-policy-service` | policy inquiry, new business, underwriting, renewal, endorsement APIs; outbox relay; Kafka-to-MQ bridge; ops event APIs | [policy-inquiry-api.md](policy-inquiry-api.md), [new-business-underwriting.md](new-business-underwriting.md), [policy-change-api.md](policy-change-api.md), [messaging-and-broker-portal.md](messaging-and-broker-portal.md) |
| `pas-batch` `premiumRecalculation` | nightly premium run (was PAS0050, 01:00) | [premium-batch.md](premium-batch.md) |
| `pas-batch` `claimsDailyExtract` | Claims feed over SFTP (was PAS0100, 23:00) | [extracts.md](extracts.md) |
| `pas-batch` `actuarialMonthlyExposure` | Actuarial feed over SFTP (was PAS0200, 1st 02:00) | [extracts.md](extracts.md) |
| `pas-batch` migration jobs | delta sync, reconciliation, reverse sync during the rollback window only | [data-migration.md](data-migration.md) |
| PostgreSQL ACMEINS | system of record after cutover | [data-model.md](data-model.md) |

## Alerts and first response

| Alert (source) | Meaning | First response |
|---|---|---|
| `PasPremiumJobFailed`, `PasPremiumRunMissing`, `PasPremiumRunSlow` (`pas/deploy/premium/alerts.yaml`) | premium run failed, missing or over 30 minutes | job logs; restart per [premium-batch.md](premium-batch.md); never run PAS0050 in parallel |
| `PasExtractJobFailed`, `PasClaimsExtractMissing`, `PasActuarialExposureMissing` (`pas/deploy/extracts/alerts.yaml`) | feed not delivered | job logs and receipts; rerun for the business date per [extracts.md](extracts.md); tell the feed owner before their cut-off |
| `PasReconciliationFailed`, `PasReconciliationMissing`, `PasDeltaSyncFailing` (`pas/deploy/migration/alerts.yaml`) | DB2 and PostgreSQL differ or sync is failing (rollback window only) | DBA; a difference older than one business day is a rollback trigger |
| Parked outbox rows / dead letters (`/api/v1/ops/events/...`) | events not delivered to Kafka or MQ | inspect with `pas.events.read`, replay with `pas.events.replay` ([messaging-and-broker-portal.md](messaging-and-broker-portal.md)) |
| Readiness DOWN / 5xx rate | API unavailable | platform runbook: pods, database connectivity, IdP reachability |

## Severity and escalation

Sev1: no policy can be written or read, or a downstream feed is missed. Sev2: one capability down or
wrong results for a subset of policies. During hypercare every Sev1/Sev2 is also recorded in
`docs/pas/cutover.json` `hypercare.incidents`. Escalation: on-call SRE, then the PAS service owner,
then the cutover lead while the rollback window is open (rollback decision,
[rollback-runbook.md](rollback-runbook.md)). On-call rota and contacts are recorded in the incident
tool (FU-530-3).

## Handover checklist

| Item | Status |
|---|---|
| On-call rota includes PAS with trained engineers | pending |
| Dashboards for both deployables and every job reviewed with operations | pending |
| Every alert above routed to the PAS on-call and walked through | pending |
| Restart and rerun procedures rehearsed in pre-production (premium, extracts, event replay) | pending |
| Access: operations hold `pas.ops.read`, `pas.events.read`, `pas.events.replay` via the IdP; no shared accounts | pending |
| Backup and restore of PostgreSQL ACMEINS tested, RPO/RTO agreed | pending |
| Known open items understood: [open-questions.md](../modernization/open-questions.md) (FU-526-1 default-off endorsement premium, KD-09..KD-11, FU-530-*) | pending |
| Mainframe support contract for PAS ended after decommission (G4-G7) | pending |

## Sign-offs

| Party | Status |
|---|---|
| Operations (service owner) | pending |
| Platform SRE | pending |
| PAS tech lead | pending |
| ARB (architecture docs) | pending |
