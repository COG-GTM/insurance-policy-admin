# ARB submission: ADR 0001, PAS target architecture

- **ADR:** [0001-pas-target-architecture.md](../0001-pas-target-architecture.md)
- **Ticket:** AB-518 (epic AB-516)
- **ARB ticket:** not raised yet (this run does not create Jira tickets; see [How to file](#how-to-file))
- **Requested ARB outcome:** approve ADR 0001 so that AB-519..AB-530 can build against it
- **Status:** ready to submit, ARB review pending

## How to file

1. Create the ARB ticket in the ARB Jira project with the title and description below.
2. Link it to AB-518 ("is blocked by" from AB-518, or the ARB project's standard link) and to epic AB-516.
3. Replace "not raised yet" above and the `ARB ticket` row in the sign-off table with the ARB key.
4. When the ARB decides, set the ADR status to `Accepted` (or record the conditions) and update [`0001-components.json`](../0001-components.json) review fields.

**Proposed ARB ticket title:** `ARB: PAS modernization target architecture (ADR 0001, AB-518)`

**Proposed ARB ticket description:**

> Acme PAS (COBOL/CICS/DB2 z/OS) is being migrated to Java 21 / Spring Boot 3 on the standard container platform under epic AB-516. ADR 0001 (docs/adr/0001-pas-target-architecture.md in COG-GTM/insurance-policy-admin) proposes: one policy service plus one Spring Batch application (D1, D2); PostgreSQL as the target database with DB2 as the only system of record until the parallel run, log-based CDC and reverse CDC for rollback (D3); Kafka domain events with a transactional outbox and an IBM MQ bridge for FileNet and the underwriting queue (D4); OAuth 2.0 / OIDC with scopes and object-level authorisation on every endpoint (D5); SFTP through the enterprise MFT service for the Claims and Actuarial feeds (D6); per-capability strangler routing with an explicit definition of "moved" (D7); platform observability (D8); and column-level protection of policyholder PII (D9). It introduces 16 new components and data stores listed for Security and Data governance review. ARB is asked to approve the decisions and answer the questions A-1..A-9.

## Decisions for approval

| Decision | Summary | ARB ask |
|---|---|---|
| D1 | One `pas-policy-service` + one `pas-batch` from a modular Maven codebase | Approve service boundaries |
| D2 | Java 21, Spring Boot 3, Spring Batch 5, containers on the standard platform, platform scheduler | Approve runtime; confirm platform (A-1) |
| D3 | PostgreSQL 16 target; DB2 sole system of record until AB-529 sign-off; log-based CDC; reverse CDC for rollback; no dual writes | Approve database and coexistence; CDC product (A-2, A-3) |
| D4 | Kafka events via transactional outbox; MQ bridge for legacy consumers; durable audit table; outbox and audit tables in a new DB2 schema `PASAPP` while DB2 is the system of record | Approve messaging (A-4, A-9) |
| D5 | OAuth 2.0 / OIDC resource servers, scopes, object-level authorisation, locked-down management endpoints | Approve AuthN/AuthZ (A-5) |
| D6 | SFTP via MFT with checksums and delivery confirmation; layouts unchanged | Approve file transfer (A-6) |
| D7 | Per-capability routing (LEGACY/SHADOW/NEW/RETIRED), one writer per capability, definition of moved | Approve strangler approach and exit criteria |
| D8 | Micrometer, OpenTelemetry, JSON logs, batch run summaries and paging | Approve observability baseline |
| D9 | Storage + column-level encryption/tokenisation of SSN last 4 and tax id; masked data outside production | Approve PII approach (A-7) |

## Questions for the ARB

| # | Question | Needed by |
|---|---|---|
| A-1 | Which container platform and scheduler are standard for this workload (Kubernetes CronJob or the enterprise scheduler)? | AB-519, AB-523 |
| A-2 | Is managed PostgreSQL 16 available in every environment, with the required HA, backup and point-in-time recovery? | AB-521 |
| A-3 | Which log-based CDC product for Db2 z/OS is approved and licensed (both directions)? | AB-529 |
| A-4 | Is managed Kafka with schema registry and Kafka Connect available? If not, approve IBM MQ on distributed platforms as the fallback. | AB-525, AB-528 |
| A-5 | Which IdP and token claims carry agent/branch/book entitlements, and who approves client registrations? | AB-524 |
| A-6 | Is there an enterprise MFT service, and what is the onboarding path for Claims and Actuarial endpoints? | AB-527 |
| A-7 | Column-level encryption vs tokenisation for SSN last 4 and tax id; key ownership. | AB-521 |
| A-8 | Retention for the audit table (FU-09), outbox, topics and shadow schema as proposed in the component register. | AB-525, AB-526, AB-528, AB-529 |
| A-9 | Can the DBA team approve a new DB2 schema `PASAPP` (outbox and audit tables, applied from AB-525's Flyway migrations through the DBA change process) for the transition period? | AB-525 |

## New components and data stores for Security and Data governance review

Generated from [`0001-components.json`](../0001-components.json). Full details (purpose, data, encryption, retention) are in the [ADR](../0001-pas-target-architecture.md#new-components-and-data-stores).

<!-- BEGIN GENERATED: review-checklist -->
| ID | Component | Replaces | Classification | PII | Introduced by | Security review | Data governance review |
|---|---|---|---|---|---|---|---|
| SVC-01 | pas-policy-service | CICS PNEW, PRWL, PEND, PQRY, PUWR and java-facade | Restricted | yes | AB-519 | pending | pending |
| SVC-02 | pas-batch | PREMIUM-BATCH, DAILY-EXTRACT, MONTHLY-EXPOSURE JCL and CA-7 PAS0050/PAS0100/PAS0200 | Restricted | yes | AB-519 | pending | pending |
| DS-01 | PostgreSQL schema acmeins (target system of record) | DB2 for z/OS subsystem DBPD, schema ACMEINS (except CLAIMS) | Restricted | yes | AB-521 | pending | pending |
| DS-02 | PostgreSQL schema acmeins_shadow (parallel-run outputs and reconciliation) | nothing (new) | Restricted | yes | AB-529 | pending | pending |
| DS-03 | Spring Batch job repository (schema pas_batch) | CA-7 run history and JES job logs | Internal | no | AB-523 | pending | pending |
| DS-04 | Transactional outbox table (DB2 PASAPP.EVENT_OUTBOX during the transition, PostgreSQL pasapp.event_outbox after cutover) | CICS TD queue writes (Q-13) | Confidential | yes | AB-525 | pending | pending |
| DS-05 | Policy audit table (DB2 PASAPP.POLICY_AUDIT during the transition, PostgreSQL pasapp.policy_audit after cutover) | CICS TS queue ENDORSEMENT-AUDIT (Q-23) and 8-character audit columns (Q-518-1) | Restricted | yes | AB-525 | pending | pending |
| DS-06 | Class-factor reference table (PASAPP.CLASS_FACTORS / pasapp.class_factors) | Hardcoded 1.00 class factor in PREMBAT (Q-03) | Internal | no | AB-521 | pending | pending |
| DS-07 | Rate reference tables (PASAPP.BASE_RATES and PASAPP.RATING_PARAMETERS / pasapp.base_rates and pasapp.rating_parameters) | Rates hardcoded in PREMBAT 3200-CALCULATE-PREMIUM (Q-27) | Internal | no | AB-522 | pending | pending |
| MSG-01 | Kafka topics pas.policy.* and pas.underwriting.* with schema registry subjects | TD queue ACME routed to IBM MQ ACME.PAS.UNDERWRITING.REQUEST/DECISION | Confidential | yes | AB-528 | pending | pending |
| MSG-02 | CDC pipeline DB2 to PostgreSQL (and reverse after cutover) | nothing (temporary, removed in AB-530) | Restricted | yes | AB-529 | pending | pending |
| INT-01 | Kafka to IBM MQ bridge | nothing (temporary, removed in AB-528/AB-530) | Confidential | yes | AB-528 | pending | pending |
| INT-02 | SFTP / managed file transfer flows to Claims and Actuarial | z/OS FTP client steps in DAILY-EXTRACT STEP030 and MONTHLY-EXPOSURE STEP020 (Q-32) | Confidential | no | AB-527 | pending | pending |
| INT-03 | Claims history read interface | Direct reads of ACMEINS.CLAIMS by POLRNW and UNDWRT (I-7, FU-17) | Confidential | no | AB-525 | pending | pending |
| SEC-01 | Identity provider client registrations, scopes and entitlement claims | Network segmentation only (Q-36) | Confidential | no | AB-524 | pending | n/a |
| SEC-02 | Secrets manager and KMS entries | Defaulted passwords in properties and Dockerfile (Q-36), FTP credentials in z/OS datasets | Restricted | no | AB-519 | pending | pending |
| PLT-01 | API gateway routes /api/v1/policies/** and new PAS APIs | Direct VPN access to java-facade | Internal | no | AB-524 | pending | n/a |
| PLT-02 | DB2 service account and grants for pas-policy-service and pas-batch (transition only) | nothing (temporary, revoked at cutover) | Restricted | yes | AB-525 | pending | pending |
<!-- END GENERATED: review-checklist -->

## Pre-reads

- [ADR 0001](../0001-pas-target-architecture.md)
- [Behaviour inventory](../../modernization/behaviour-inventory.md), especially §2 (CRUD matrix) and §7 (integrations I-1..I-10)
- [Quirk register](../../modernization/quirk-register.md): Q-13 (messaging), Q-23 (audit), Q-32 (FTP), Q-36 (facade security), Q-38 (batch), Q-518-1 (audit identity)
- [Open questions](../../modernization/open-questions.md): FU-06, FU-09, FU-15, FU-17

## Sign-off

Human reviews cannot happen inside this repository, so they are recorded here as pending.

| Review | Reviewer | Scope | Status | Date / reference |
|---|---|---|---|---|
| ARB ticket raised and linked to AB-518 | Programme manager | This document | pending | |
| Architecture approval | Architecture Review Board | D1..D9 | pending | |
| Security review | Security architecture | D5, D6, D9; components with a security review | pending | |
| Data governance review | Data governance / DPO | D3, D9; components holding personal or confidential data | pending | |
| Platform fit | Platform team | D2, D4, D8; A-1, A-2, A-4, A-6 | pending | |
| Database and CDC | DBA team (db2-admin@acme.com) | D3; A-3 | pending | |
| Mainframe coexistence | Mainframe team (mainframe-ops@acme.com) | D3, D4, D7 | pending | |
| Business owner | Product Owner, Policy Administration | D7 definition of moved | pending | |
| Downstream consumers | Claims, Actuarial, Broker Portal, ECM | D4, D6, D7 (I-1..I-6) | pending | |
