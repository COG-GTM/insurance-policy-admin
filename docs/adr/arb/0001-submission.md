# ARB submission: ADR 0001, PAS target architecture

Paste this into the ARB ticket when it is created. Then replace `ARB-TBD` here, in
[`../0001-pas-target-architecture.md`](../0001-pas-target-architecture.md), in
[`../README.md`](../README.md) and in Jira AB-518 with the real key.

```
## Architecture review
ADR: docs/adr/0001-pas-target-architecture.md
ARB ticket: ARB-TBD (TO BE CREATED. No ARB Jira project is reachable from the implementing session)
Jira: AB-518 (epic AB-516, PAS modernization off COBOL/CICS/DB2)
Triage: ARB_REQUIRED (T1, T2, T4, T6, T7, T8; T3/T5/T9 to be confirmed)
Summary: Replace CICS/COBOL/DB2 PAS with two Java 21 / Spring Boot 3 containers
  (pas-policy-service, pas-batch) on PostgreSQL 16 + Kafka. OIDC/JWT authZ, SFTP feeds,
  CDC-based DB2 coexistence, and per-capability strangler routing with an explicit "moved" rule.
Exceptions requested: none (pending ARB confirmation that PostgreSQL, Kafka, gateway and CDC tool are approved offerings)
Reviewers needed: Security, Data governance, Platform, Mainframe/Integration
Merge policy: ADR PR may merge to the epic integration branch; merge to main waits on ARB approval
```

## Decisions asked of the ARB

| # | Decision | Ratifies / amends AB-519 provisional pick |
| --- | --- | --- |
| D1 | One `pas-policy-service` (inquiry, lifecycle, rating, underwriting) + one `pas-batch`, not one service per CICS transaction. Rating is an in-process library | Ratifies |
| D2 | Java 21, Spring Boot 3, Spring Batch 5, OCI containers on the standard platform | Ratifies, **amended**: Boot 3.3 is past OSS support, so upgrade to a supported 3.5.x before any capability goes live |
| D3 | PostgreSQL 16 + Flyway (`pas`, `pas_batch`). Bulk load + one-way CDC from DB2. DB2 stays the system of record until AB-529, with reverse replication during the rollback window | Ratifies PostgreSQL 16 + Flyway; adds the coexistence model |
| D4 | Kafka with versioned schemas, transactional outbox and no PII in events. Transitional Kafka→MQ bridge for FileNet | Ratifies Kafka; adds outbox and bridge |
| D5 | OAuth2/OIDC JWT resource server; one scope per capability; object-level entitlement on agent/branch/book claims; actuator internal only | New |
| D6 | SFTP (key auth, pinned host key) + SHA-256 manifest + delivery receipt, replacing plain FTP | New |
| D7 | Gateway with per-capability route flags (`legacy`/`shadow`/`new`/`retired`) and a six-point "moved" rule | New |

## Triage

The automated `arb-triage` detector wasn't run: the session's guardrails block executing plugin scripts. These triggers were judged by hand against the org `arb-triggers` rule.

| Trigger | Hit? | Evidence |
| --- | --- | --- |
| T1 New deployable / scheduled job | **Yes** | `pas-policy-service`, `pas-batch` (scheduled jobs replacing CA-7/JCL), CDC pipeline, Kafka→MQ bridge |
| T2 New data store / classification change | **Yes** | PostgreSQL `pas` (Restricted PII from POLICY_HOLDERS), `pas_batch`, outbox and audit tables, Kafka topics |
| T3 New external vendor | **To confirm** | CDC product for DB2 z/OS; managed Kafka / schema registry / gateway if not already approved |
| T4 New cross-domain contract | **Yes** | Kafka events replacing TDQ/MQ (FileNet), REST APIs for Broker Portal, SFTP delivery contract for Claims/Actuarial, Claims open-claims interface |
| T5 Infra outside approved policy | **To confirm** | `policy/approved-infra.yaml` isn't in the repo. No IaC in this change |
| T6 AuthN/AuthZ or network boundary | **Yes** | New OIDC integration, new public APIs, SFTP egress, CDC channel from z/OS |
| T7 Runtime introduction | **Yes** | Java 21 / Boot 3 replacing COBOL/CICS and Boot 1.5 |
| T8 Spans >1 team | **Yes** | PAS, mainframe, Claims, Actuarial, Broker Portal, FileNet, platform, security |
| T9 Cost > $2k/month | **TBD** | No estimate yet. Listed as an open question |

## New components and data stores

The full table, with classification, lifetime and owner, is in the ADR under
[New components and data stores](../0001-pas-target-architecture.md#new-components-and-data-stores-for-security-and-data-governance-review).
Short version:

- **Permanent:**
  - deployables `pas-policy-service` and `pas-batch`;
  - PostgreSQL schemas `pas` (Restricted) and `pas_batch`, including the `pas.outbox` table and the audit tables;
  - Kafka topics `pas.policy.events.v1`, `pas.underwriting.events.v1`, `pas.document.requests.v1` and their schemas;
  - gateway routes;
  - IdP clients and scopes;
  - SFTP keys.
- **Transitional:** the DB2↔PostgreSQL CDC pipeline (including reverse replication for rollback) and the Kafka→MQ bridge.

## Known gaps the ARB should see

Numeric NFRs (SLO, latency, RPO/RTO, peak load), cost, data residency and retention are
**TBD** and listed under *Open questions* in the ADR. They have not been invented.
Write-capability cutover also depends on an unowned replacement for the CICS 3270 channel (ADR D7).

## Sign-offs

- Pending: ARB
- Pending: Security
- Pending: Data governance
- Pending: Platform team
- Pending: Mainframe team
- Pending: PAS business owner
