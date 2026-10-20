# 0003. Retire PAS legacy messaging and Broker Portal DB2 access (AB-528)

- **Status:** Proposed
- **Date:** 2026-10-20
- **ARB ticket:** TO BE CREATED (no Jira writes from this ticket; Pending: ARB)
- **Authors:** PAS platform (AB-528)
- **Owning team:** PAS platform
- **Related ADRs:** [0001](0001-pas-target-architecture.md) (D4 events/outbox, D5 Broker Portal via
  `pas-policy-service`, transitional Kafka→MQ bridge), [0002](0002-pas-target-data-model-and-pii.md)

## Context

PAS still talks to three consumers through mainframe mechanisms: CICS TD queues to Underwriting
(`ACME.PAS.UNDERWRITING.REQUEST`/`DECISION`), the `ENDORSEMENT-AUDIT` TS queue, MQ events that
trigger FileNet documents, and a SQL Server linked server that lets the Broker Portal read DB2
directly ([integrations.md](../modernization/integrations.md) I-03..I-07). All of them must go
before DB2 and the CICS region retire. ADR 0001 already chose Kafka + transactional outbox, a
transitional Kafka→MQ bridge rebuilding the 125-byte `POLICY-RECORD` from `policyVersion`, and the
inquiry API for the portal. This ADR records how AB-528 implements that and asks the ARB to review
the concrete contracts and stores.

Constraints: real MQ/FileNet endpoints and the portal's query list are not available (M-04,
M-08); downstream consumers depend on the fixed-width layout byte for byte; no new deployable
without an ADR addendum (ADR 0001).

ARB triggers (manual triage, `rules/arb-triggers.md`; the detector script is blocked by the
guardrails plugin on this VM):

- **T2** new tables in the `pas` PostgreSQL schema: `pas.policy_versions` (policy row per
  version, holder ID only, no PII), `pas.processed_events`, `pas.dead_letters` (raw event
  payloads, same classification as `pas.outbox`).
- **T4** new/changed async contracts: v1 JSON Schemas for six event types, new event types
  `PolicyRenewed`, `PolicyEndorsed`, `DocumentRequested`, topic `pas.document.requests.v1`, DLT
  topics `<topic>.dlt`, Kafka→legacy-queue bridges.
- **T6** new authenticated endpoints: operator API `/api/v1/admin/**` (`batch:operate`), portal
  read endpoints `GET /api/v1/policies?agentCode|branchCode` and `/{n}/premiums` (`policy:read`,
  object authz), a new client (Broker Portal) of `pas-policy-service`.
- **T8** spans PAS platform, Document/FileNet, Underwriting, Broker Portal and Mainframe teams.
- Not triggered: T1 (bridges live in the existing `pas-policy-service`), T3 (no new vendor; one
  test-scope library `com.networknt:json-schema-validator`), T5 (no infrastructure code), T7, T9.

## Decision

We will publish every policy domain event through one `PolicyEventPublisher` that, inside the
business transaction, snapshots the committed policy row at its `policyVersion`, appends the
domain event to the AB-525 outbox and, for new business/renewal/endorsement, appends a
`DocumentRequested` event; contracts are versioned JSON Schemas with a backward-compatibility
test. Inside `pas-policy-service` (no new deployable), Kafka consumers bridge `DocumentRequested`
to the FileNet trigger and `PolicyCreated`/underwriting events to the two underwriting queues by
rebuilding the exact CICS `POLICY-RECORD` message (`LegacyPolicyMessages`, golden-tested against
GnuCOBOL captures) behind a `LegacyQueueTransport` interface; consumers are idempotent on
`eventId`, retry with exponential backoff, dead-letter to `<topic>.dlt`, and operators replay
from `pas.dead_letters` through a `batch:operate` API. The Broker Portal reads through the
inquiry API (two new read endpoints), not a read replica. Bridges are off by default
(`pas.integration.bridges-enabled`) until FileNet and Underwriting agree the cut-over.

## Alternatives considered

| Alternative | Pros | Cons | Why rejected |
| --- | --- | --- | --- |
| Do nothing (keep TDQ/MQ + linked server) | No work | Blocks DB2/CICS retirement (epic goal) | Not an option |
| FileNet/Underwriting consume Kafka directly now | No bridge to retire later | Owning teams not available; their consumers expect the 125-byte MQ message | Becomes the follow-up; bridge is transitional per ADR 0001 |
| Bridge as a separate deployable | Independent scaling/failure domain | New service needs an ADR addendum, pipeline, on-call | Load is one message per policy change; existing service is enough |
| Rebuild the record from the latest policy row (no snapshots) | No new table | Wrong bytes when the policy changed again before the consumer ran | ADR 0001 requires the exact `policyVersion` |
| Broker Portal via PostgreSQL read replica + linked server | Smallest portal change | Schema becomes a contract, no entitlement/PII masking, DB credential and network path for the portal | See [broker-portal-migration.md](../modernization/broker-portal-migration.md) |
| Spring Kafka non-blocking retry topics (`-retry-n`) | No partition blocking | More topics, reorders events of one policy | Blocking backoff keeps per-policy order; retries are short |

## Architecture

```mermaid
flowchart LR
  subgraph PAS["pas-policy-service (existing deployable)"]
    API["REST API\n(OIDC JWT)"] --> PUB["PolicyEventPublisher"]
    PUB -->|same tx| PG[("PostgreSQL pas:\noutbox, policy_versions")]
    RELAY["OutboxRelay (AB-525)"] --> PG
    BR["LegacyQueueBridge\n(filenet / uw-request / uw-decision)"] -->|snapshot at policyVersion,\nprocessed_events| PG
    REC["DeadLetterRecorder"] -->|dead_letters| PG
    ADM["/api/v1/admin/** (batch:operate)"] -->|replay| K
  end
  RELAY -->|Kafka, acks=all| K[["Kafka: pas.policy.events.v1,\npas.underwriting.events.v1,\npas.document.requests.v1"]]
  K --> BR
  BR -->|fail after retries| DLT[["<topic>.dlt"]] --> REC
  BR -->|LegacyQueueTransport\n(MQ/JMS: Pending)| FN["FileNet document trigger\n(Document/FileNet team)"]
  BR -->|LegacyQueueTransport| UW["ACME.PAS.UNDERWRITING.*\n(Underwriting team)"]
  BP["Broker Portal (SQL Server)"] -->|HTTPS + OIDC client creds,\npolicy:read| API
  OPS["Operator"] -->|HTTPS + JWT batch:operate| ADM
```

## Non-functional requirements

| NFR | Target | How met |
| --- | --- | --- |
| Availability SLO | TBD — PAS platform to confirm before ARB (inherits pas-policy-service) | Bridges run in every replica; Kafka group rebalancing |
| p95 latency | Document trigger within 60 s of commit (TBD — FileNet team) | Outbox poll 1 s, bridge consumes immediately |
| RPO / RTO | RPO 0 for events (outbox in the business tx); RTO = pas-policy-service RTO | Outbox + Kafka `acks=all`; dead letters persisted |
| Peak load | TBD — mainframe team to supply PNEW/PRWL/PEND daily peaks | ~3 Kafka records + 1–2 queue messages per policy change |
| Scaling model | Horizontal with pas-policy-service replicas, bounded by topic partitions | Per-policy ordering via key = policyNumber |
| Data retention | `policy_versions`, `processed_events`, `dead_letters`: TBD — data governance | Proposed purge job (open-questions.md) |

## Security & compliance

- **Data classification:** events and snapshots carry identifiers and business facts (holder ID,
  agent/branch, amounts), no policyholder PII; same class as `pas.outbox` (Confidential).
- **Encryption at rest:** PostgreSQL storage encryption per ADR 0001; no new PII columns.
- **Encryption in transit:** Kafka TLS and HTTPS per ADR 0001; MQ channel TLS is Pending:
  Document/FileNet team (transport not built).
- **AuthN / AuthZ:** OIDC JWT; `/api/v1/admin/**` needs `batch:operate`; portal endpoints need
  `policy:read` and apply AB-524 object authorization (not entitled = omitted / 404).
- **Secrets management:** no new secrets in code; future MQ credentials via the platform secret
  store (Pending: Security).
- **Audit logging:** replays record `replayed_by` (JWT subject) and count; bridge logs eventId,
  policy, version, queue (no payload).
- **Data residency / regions:** unchanged (ADR 0001).
- **Policy sections satisfied:** no infrastructure in this change; `policy/approved-infra.yaml`
  not applicable.
- **Threats considered:** replay abuse (scope-gated, idempotent consumers), poison messages
  (non-retryable → DLT), cross-tenant reads by the portal (object authz), payload tampering on
  Kafka (internal network + ACLs, Pending: Security).

## Cost

| Item | Assumption | Monthly estimate |
| --- | --- | --- |
| Kafka topics | 1 new topic + 3 DLT topics on the existing cluster | TBD — platform team (expected negligible) |
| PostgreSQL storage | 3 small tables in the existing database | TBD — expected negligible |
| **Total** | No new services or clusters | TBD — expected well under $500/month |

## Operations

- **On-call rotation:** PAS platform (pas-policy-service rotation).
- **Runbook:** [docs/modernization/events.md](../modernization/events.md) "Dead letters and
  replay"; decommission order in [queue-decommission.md](../modernization/queue-decommission.md).
- **Dashboards / alarms:** existing outbox gauges; alarm on any `PARKED` dead letter (proposed).
- **Rollback plan:** set `PAS_INTEGRATION_BRIDGES_ENABLED=false`; legacy queues keep running until
  decommission, so rollback is a flag flip during the parallel run.
- **Migration / cut-over plan:** enable bridges in test with each consumer, then production
  during the parallel run (AB-529); portal switches query by query; decommission after AB-530.

## Policy exceptions requested

None.

## Open questions

- Real FileNet trigger queue, MQ/JMS transport and message descriptor (Pending: Document/FileNet
  team, M-04).
- Consumer of the underwriting queues (Pending: Underwriting team, OQ-12).
- Portal query list and client registration (Pending: Broker Portal team, Security; OQ-14).
- NFR numbers and retention marked TBD above.
