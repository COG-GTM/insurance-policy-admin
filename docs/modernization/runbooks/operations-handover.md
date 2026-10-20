# PAS operations handover (AB-530)

What the operations / on-call team needs to run PAS on `pas-platform` after the cutover. Each
section points at the detailed runbook rather than repeating it.

| | |
|---|---|
| Handover session with Ops | **Pending: Ops** (date, attendees and accepted actions go in the change record) |
| Ops acceptance of this document | **Pending: Ops** |
| Until cutover | Mainframe team keeps on-call for legacy PAS (ADR 0001 Operations) |

## 1. What runs where

| Component | What it does | Replaces | Runbook |
|---|---|---|---|
| `pas-policy-service` (:8080) | Inquiry, new business, underwriting, renewal and endorsement APIs; `pas.policy_audit` audit trail; transactional outbox relay to Kafka | CICS PQRY/PNEW/PUWR/PRWL/PEND, `java-facade` | [facade-retirement.md](../facade-retirement.md), [events.md](../events.md) |
| `pas-batch` (:8081) | `premiumRecalculationJob`, `dailyExtractJob`, `monthlyExposureJob`, SFTP delivery; transitional DB2 migration jobs | PREMBAT/PAS0050, DAILY-EXTRACT/PAS0100, MONTHLY-EXPOSURE/PAS0200 | [premium-batch.md](premium-batch.md), [extracts-runbook.md](../extracts-runbook.md), [data-migration.md](../data-migration.md) |
| PostgreSQL 16 | schema `pas` (system of record, Flyway in pas-policy-service), `pas_batch` (Spring Batch metadata), `pas_shadow` (transitional, dropped in decommission D-11) | DB2 ACMEINS | [ADR 0002](../../adr/0002-pas-target-data-model-and-pii.md) |
| Kafka | `pas.policy.events.v1`, `pas.underwriting.events.v1`, `pas.document.requests.v1` | CICS TDQ/TSQ + MQ | [events.md](../events.md) |
| Routing layer | per-capability route flags (`pas-platform/routing/route-flags.json`) | — | [cutover.md §5](cutover.md#5-routing-switch-per-capability) |

## 2. On-call

| Item | Value |
|---|---|
| Rotation | PAS Platform team. Rotation name and paging tool entry: **Pending: Ops** (ADR 0001 "TBD: rotation name") |
| Escalation | PAS Platform tech lead → PAS Platform engineering manager → Business owner (data issues) |
| Mainframe standby | Mainframe ops lead until decommission D-03 (rollback needs them) |
| Other teams | Claims (claims adapter, Claims feed), Actuarial (exposure feed), Broker Portal, FileNet/ECM (`pas-filenet-document-bridge`, transport Pending: Document/FileNet team), Underwriting (`ACME.PAS.UNDERWRITING.*` bridges), Platform team (gateway, Kafka, PostgreSQL), Security (keys, IdP) |
| Severity | Enterprise Sev 0–3 rubric. Sev1 examples: policy writes failing, premium run FAILED with no restart before 06:00, Claims feed missing on a business day, data-integrity defect |

## 3. Dashboards and metrics

All from Spring Boot actuator `/actuator/prometheus` (pas-policy-service needs a JWT; pas-batch
exposes it internally only). Dashboard build: **Pending: Ops**.

| Area | Metrics |
|---|---|
| API | `http_server_requests_seconds{uri,status}` (rate, error rate, p95 latency per endpoint); JVM and Hikari pool metrics |
| Outbox / events | `pas.outbox` backlog: `SELECT COUNT(*), MIN(created_at) FROM pas.outbox WHERE published_at IS NULL` (no gauge yet, see open questions "AB-530") |
| Premium batch | `pas_batch_premium_runs_total{outcome}`, `pas_batch_premium_run_duration_seconds{outcome}`, `pas_batch_premium_last_run_policies{result}`, `pas_batch_job_runs_total{job,status}` |
| Extracts / SFTP | `pas_extract_records_total{job,file}`, `pas_transfer_deliveries_total{destination,outcome}`, `pas_transfer_attempt_failures_total`, `pas_transfer_cleanup_failures_total`, `pas_transfer_duration_seconds` |
| Migration (rollback window only) | `pas_migration_unexplained{check}`, `pas_migration_differences{check,classification}`, `pas_migration_check_passed{check}`, `pas_migration_last_run_seconds{check}` |
| Health | `/actuator/health/liveness`, `/actuator/health/readiness` (readiness includes the database) on both services |

## 4. Alerts

Suggested rules; wiring into Alertmanager / the paging tool is **Pending: Ops**.

| Alert | Condition | Response |
|---|---|---|
| Premium run needs attention | `increase(pas_batch_premium_runs_total{outcome!="COMPLETED"}[1h]) > 0` | [premium-batch.md](premium-batch.md) "Alert response" |
| Premium run missing | `increase(pas_batch_premium_runs_total{outcome="COMPLETED"}[26h]) == 0` | start / restart the run |
| Premium run slow | `pas_batch_premium_run_duration_seconds > 1500` (25 min, ADR 0001 early warning before 30 min) | check DB load, partitions |
| Batch job failed | `increase(pas_batch_job_runs_total{status!="COMPLETED"}[1h]) > 0` or logger `pas.batch.alert` ERROR | job runbook |
| Extract empty | `pas_extract_records_total{file="policy_extract.dat"}` did not increase on a business day | [extracts-runbook.md](../extracts-runbook.md) "Failure handling" |
| SFTP delivery failed | `increase(pas_transfer_deliveries_total{outcome!="SUCCESS"}[1h]) > 0` | SFTP failure table in the extracts runbook |
| API errors | 5xx rate > 1% for 5 min, or p95 above the SLO (SLO **Pending: Business owner**) | logs, DB, outbox |
| Outbox backlog | oldest unpublished `pas.outbox` row older than 5 min | Kafka health; relay logs (`pas.outbox.relay.*`) |
| Reconciliation (rollback window) | `pas_migration_unexplained > 0` or `pas_migration_last_run_seconds` older than 26 h | [parallel-run.md](../parallel-run.md) triage |

## 5. Batch schedule replacing CA-7

| CA-7 job | Legacy | pas-batch job | Schedule (America/New_York) | Config |
|---|---|---|---|---|
| PAS0050 | PREMIUM-BATCH / PREMBAT, daily 01:00 | `premiumRecalculationJob` | daily 01:00 | `PAS_BATCH_PREMIUM_CRON="0 0 1 * * *"`, `PAS_BATCH_PREMIUM_ZONE`, `PAS_BATCH_PREMIUM_WRITE_TARGET=pas` |
| PAS0100 | DAILY-EXTRACT (POLEXT), daily 23:00 | `dailyExtractJob` + SFTP to Claims | daily 23:00 | `PAS_SCHEDULE_ENABLED=true`, `PAS_DAILY_EXTRACT_CRON` (default `0 0 23 * * *`) |
| PAS0200 | MONTHLY-EXPOSURE (MONEXP), 1st 02:00 | `monthlyExposureJob` + SFTP to Actuarial | 1st of month 02:00 | `PAS_MONTHLY_EXPOSURE_CRON` (default `0 0 2 1 * *`) |
| — | — | DB2 delta / reconcile / parallel-run (transitional) | 15 min / 05:00 / 06:00 until the flip | `PAS_MIGRATION_SCHEDULE_ENABLED` (false after the flip) |

Predecessor/successor dependencies of the CA-7 jobs are not in the repo ([OQ-16](../open-questions.md#oq-16));
confirm none are lost before D-04 (**Pending: Batch operations**). Run only one scheduling
replica of pas-batch (open question "Single-launcher guarantee").
Manual runs, reruns and restarts: [premium-batch.md](premium-batch.md), [extracts-runbook.md](../extracts-runbook.md).

## 6. Secrets and key rotation

| Secret | Used by | Rotation runbook |
|---|---|---|
| `PAS_PII_ENCRYPTION_KEY`, `PAS_PII_BLIND_INDEX_KEY`, `PAS_PII_KEY_ID`, `pas.pii.retired-keys.*` | both services | [pii-handling.md](../pii-handling.md) "Rotation" (new key id, old key to retired keys, re-encrypt, remove). KMS/owner/period: Pending: Security |
| `PAS_OIDC_ISSUER_URI`, `PAS_OIDC_AUDIENCE` (IdP signing keys via JWKS) | both services | IdP-managed; rotation is transparent through the issuer's JWKS. Client credentials of consumers: IdP team |
| SFTP private keys and passphrases (`PAS_CLAIMS_SFTP_*`, `PAS_ACTUARIAL_SFTP_*`), `PAS_SFTP_KNOWN_HOSTS_FILE` | pas-batch | [extracts-runbook.md](../extracts-runbook.md) (host-key change: confirm out of band, update `known_hosts`; never disable verification) |
| `PAS_DB_USERNAME` / `PAS_DB_PASSWORD` | both services | Platform secrets store rotation (Pending: Platform team) |
| `PAS_MIGRATION_SOURCE_*` (DB2 read-only user) | pas-batch, rollback window only | removed in decommission D-02 |
| Facade DB2 credentials | `java-facade` | revoked in decommission D-09 |

## 7. Common procedures

| Situation | Where |
|---|---|
| Premium run failed / completed with errors / rerun a date | [premium-batch.md](premium-batch.md) |
| Extract failed, SFTP redelivery, host key changed | [extracts-runbook.md](../extracts-runbook.md) |
| Event not received downstream (outbox stuck) | [events.md](../events.md); rows with `published_at IS NULL`, `attempts`, `last_error` in `pas.outbox` |
| Reconciliation difference during the rollback window | [parallel-run.md](../parallel-run.md) triage |
| Roll back to CICS/DB2 | [rollback.md](rollback.md) |
| Consumer gets 401/403/404 | [facade-retirement.md](../facade-retirement.md) (404 = not entitled, by design) |

## 8. Handover checklist

| # | Item | Status |
|---|---|---|
| H-1 | Walk through this document, cutover.md and rollback.md with the on-call team | Pending: Ops |
| H-2 | On-call rotation and paging set up | Pending: Ops |
| H-3 | Dashboards built from section 3 | Pending: Ops |
| H-4 | Alerts from section 4 wired and test-fired | Pending: Ops |
| H-5 | Each on-call engineer ran `make cutover-rehearsal` locally once | Pending: Ops |
| H-6 | Access: production read-only DB role, pas-batch CLI (platform RBAC), gateway flag PR rights | Pending: Platform team |
| H-7 | Mainframe ops briefed on their rollback steps (R3–R6) | Pending: Mainframe ops lead |
