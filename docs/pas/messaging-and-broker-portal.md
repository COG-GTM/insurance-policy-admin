# Domain events, legacy bridge and Broker Portal access (AB-528)

ADR 0001 D4 replaces the CICS TD/TS queue writes with Kafka domain events published through the
transactional outbox (`PASAPP.EVENT_OUTBOX`, AB-525) and a temporary Kafka-to-MQ bridge (INT-01).
AB-528 completes the event set, adds versioned schemas, the bridge, dead-letter handling and replay,
and moves the Broker Portal off its DB2 linked server.

## Domain events

| Topic | Emitted by | Legacy equivalent | Schema |
|---|---|---|---|
| `pas.policy.created` | `NewBusinessService` (POLNEW) | - (FileNet trigger, I-6) | `pas.policy.created` v1 |
| `pas.policy.renewed` | `RenewalService` (POLRNW) | - (FileNet trigger, I-6) | `pas.policy.renewed` v1 |
| `pas.policy.endorsed` | `EndorsementService` (POLEND) | TSQ `ENDORSEMENT-AUDIT` | `pas.policy.endorsed` v1 |
| `pas.underwriting.requested` | `NewBusinessService` | TDQ `ACME` → `ACME.PAS.UNDERWRITING.REQUEST` | v1 |
| `pas.underwriting.decided` | `UnderwritingService` (UNDWRT) | TDQ `ACME` → `ACME.PAS.UNDERWRITING.DECISION` | v1 |

- Each event is appended to the outbox in the same transaction as the business change, so a rolled
  back renewal or endorsement emits nothing. The key is the policy number (ordering per policy).
- Envelope: `eventId`, `type`, `schemaVersion`, `policyNumber`, `occurredAt`, `correlationId`, `data`.
  Headers `eventId`, `eventType`, `schemaVersion`. No direct PII (ADR D5); holder id and agent/branch
  codes are pseudonymous.
- Schemas live in `pas/pas-events/src/main/resources/schemas/` (`v1.json` + `registry.json` with
  subject, version, `BACKWARD` compatibility and SHA-256). `EventSchemasTest` fails when a schema
  changes without a new version, when an event type has no schema, or when a produced event does not
  validate. A breaking change adds `v2.json`, bumps `PasEvent.SCHEMA_VERSION` and registers the new
  subject version in the platform schema registry (FU-528-2).

## Legacy bridge (INT-01)

With `pas.bridge.enabled=true` (`PAS_BRIDGE_ENABLED`), `LegacyRecordBridge` consumes the five topics
(group `pas-legacy-bridge`), validates each event against its schema, reads the current policy and
encodes the 125-byte EBCDIC POLICY-RECORD with `PolicyRecordCodec` (the AB-519 copybook codec):

| Event | Bridge topic | Header | Downstream |
|---|---|---|---|
| created / renewed / endorsed | `pas.bridge.filenet.document-trigger` | `documentType` = `NEW_BUSINESS` / `RENEWAL` / `ENDORSEMENT` | FileNet trigger queue |
| underwriting requested | `pas.bridge.mq.underwriting-request` | - | `ACME.PAS.UNDERWRITING.REQUEST` |
| underwriting decided | `pas.bridge.mq.underwriting-decision` | `decisionCode`, `referral` | `ACME.PAS.UNDERWRITING.DECISION` |

The IBM MQ sink connector configurations in [`pas/deploy/messaging`](../../pas/deploy/messaging/README.md)
move the bytes unchanged to the queues. The record is built from the policy row at bridge time, not
at event time (Q-528-2), and delivery is at-least-once with `eventId` for de-duplication (Q-528-1).

### Moving the consumers

| Consumer | Today | Step 1 (no change for the consumer) | Step 2 (target) | Owner | Status |
|---|---|---|---|---|---|
| FileNet document creation | MQ trigger (I-6) | bridge + sink to its queue | subscribe to `pas.policy.created/renewed/endorsed` with the v1 schemas | ECM/Document team | pending (FU-06, FU-528-1) |
| Underwriting work queue | `ACME.PAS.UNDERWRITING.REQUEST` | bridge + sink | `pas.underwriting.requested` | Underwriting ops | pending (FU-528-1) |
| Underwriting referrals | `ACME.PAS.UNDERWRITING.DECISION` | bridge + sink | `pas.underwriting.decided` (`data.referral = true`) | Underwriting ops | pending (FU-528-1) |

## Dead letters and replay

- **Outbox (publish to Kafka fails):** `OutboxRelay` retries a row on every run; after
  `pas.events.max-attempts` (default 10) it parks the row (`PARKED_AT`, V80) and logs an error. Later
  rows for the same policy wait behind it so order per policy is kept; other policies continue.
- **Bridge (an event cannot be bridged):** `DefaultErrorHandler` retries `pas.bridge.retries` times
  (`retry-interval`), then `DeadLetterPublishingRecoverer` writes the original record to
  `pas.events.dlt` with the Spring Kafka `kafka_dlt-*` headers. Schema failures, unknown events and
  missing policies go straight to the dead-letter topic.
- **Ops API** (pas-policy-service, metadata only, never payloads):

| Method | Path | Scope |
|---|---|---|
| GET | `/api/v1/ops/events/outbox/parked?limit=` | `pas.ops.read` |
| POST | `/api/v1/ops/events/outbox/{eventId}/replay` | `pas.events.replay` |
| GET | `/api/v1/ops/events/dead-letters?limit=` | `pas.ops.read` |
| POST | `/api/v1/ops/events/dead-letters/replay` body `{"eventIds":[...]}` (max 500) | `pas.events.replay` |

  Replaying an outbox row clears `PARKED_AT`, resets the attempts and increments `REPLAY_COUNT`; a
  dead letter is re-published to its original topic with header `replayOf`. Retention: 7 days on the
  business topics, 30 days on `pas.events.dlt` (MSG-01); alert on any parked row or DLT message
  (FU-528-3).

## Broker Portal without DB2

The portal's linked server (I-3) is replaced by the inquiry API (AB-524) plus a list endpoint:

| Portal need | API |
|---|---|
| Policies of an agent or branch | `GET /api/v1/policies?agentCode=A1234&status=AC&after=<policyNumber>&limit=50` (or `branchCode=`), keyset-paged by policy number, `nextAfter` in the response |
| Policy detail | `GET /api/v1/policies/{policyNumber}` |
| Coverages | `GET /api/v1/policies/{policyNumber}/coverages` |
| Holder (masked without `pas.policyholder.read`) | `GET /api/v1/policies/{policyNumber}/policyholder` |

The list requires `pas.policy.read` and returns only what the caller's entitlement covers: an
agent or branch outside `pas_agents` / `pas_branches` gets an empty page, exactly one of the two
filters is required and `limit` is at most 200. The portal authenticates with its own OAuth client
(SEC-01) through the API gateway (PLT-01) and holds no DB2 connection or grant. A read replica is not
proposed: the API already covers every documented read and keeps entitlement in one place; if FU-18
shows queries the API cannot serve, the portal team and DBA decide between a new endpoint and a
replica (FU-528-4).

## Acceptance evidence

| Criterion | Automated evidence | Pending outside code |
|---|---|---|
| Documents still generated for new, renewed and endorsed policies | `MessagingBridgeIT` creates, renews and endorses a policy through the HTTP APIs with PostgreSQL and Kafka (Testcontainers) and asserts a 125-byte POLICY-RECORD with `documentType` `NEW_BUSINESS`, `RENEWAL`, `ENDORSEMENT` on the FileNet trigger topic, plus the underwriting request | G2: FileNet produces the documents in a test environment from the sink/queue (ECM) |
| Broker Portal works with no connection to DB2 | `PolicyInquiryApiIT` (list, paging, entitlement, validation) and the OpenAPI contract `policy-inquiry-v1.yaml` | G4: the portal team switches the portal and disables the linked server |
| Old queue definitions listed for decommission | `docs/pas/messaging-decommission.json` / `.md`, `make messaging-decommission` in CI | G1, G5-G7 and the steps S1-S6 |
| Dead letters and replay | `MessagingBridgeIT` (DLT listing + replay, parked outbox replay through the ops API), `OutboxRelayTest`, `NewBusinessPersistenceIT` | alerting (FU-528-3) |

## Sign-offs

| Area | Who | Status |
|---|---|---|
| Event schemas and topics (partitions, retention, ACLs) | Platform team | pending |
| FileNet document trigger move | ECM/Document team | pending |
| Underwriting request/referral move | Underwriting ops | pending |
| MQ sink deployment and queue decommission | MQ admins | pending |
| Broker Portal on the inquiry API | Broker Portal team | pending |
| Revoking the DB2 linked-server grants | DBA team | pending |
| Events and bridge payload (pseudonymous ids) | Security + Data governance | pending |
| Components MSG-01, INT-01 | ARB | pending |
