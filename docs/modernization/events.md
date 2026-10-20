# PAS domain events (AB-525, AB-528)

pas-policy-service publishes domain events to Kafka through a transactional outbox
(ADR 0001 D4). AB-528 confirms the topic and event names below, adds versioned JSON Schemas and
the legacy queue bridges (FileNet document trigger, underwriting queues) with dead-letter replay.
Publish through `PolicyEventPublisher` (never `OutboxWriter` directly for policy events): it
snapshots the policy at `policyVersion` (`pas.policy_versions`, V9) so consumers can rebuild the
exact `POLICY-RECORD`.

## Delivery

- The business transaction writes the event to `pas.outbox` (Flyway V6) together with the
  state change; `OutboxRelay` publishes rows in `outbox_id` order and sets `published_at`.
- One replica relays at a time (`pg_try_advisory_xact_lock`), so events for a policy keep
  their order. A failed send stops the batch, records `attempts`/`last_error` and is retried
  on the next poll. Delivery is **at least once**: consumers dedupe on `eventId`.
- Record key = `policyNumber`; headers `eventType`, `eventId`; value = the JSON envelope.
- Metrics: `pas_outbox_pending`, `pas_outbox_oldest_pending_age_seconds` (alarm on backlog age).
- Config: `pas.events.policy-topic` (`PAS_POLICY_EVENTS_TOPIC`, default `pas.policy.events.v1`),
  `pas.events.underwriting-topic` (`PAS_UNDERWRITING_EVENTS_TOPIC`, default
  `pas.underwriting.events.v1`), `pas.events.document-topic` (`PAS_DOCUMENT_REQUESTS_TOPIC`,
  default `pas.document.requests.v1`), `pas.outbox.relay.enabled` (`PAS_OUTBOX_RELAY_ENABLED`),
  `pas.outbox.relay.poll-interval` (`PAS_OUTBOX_RELAY_POLL_INTERVAL`, default `PT1S`),
  `pas.outbox.relay.batch-size`, `pas.outbox.relay.send-timeout`.
- Published rows are not purged yet (retention TBD by data governance, see open questions).

## Envelope (schemaVersion 1)

```json
{
  "eventId": "7d0c…",            "eventType": "PolicyCreated",  "schemaVersion": 1,
  "occurredAt": "2026-10-20T16:00:00Z",  "policyNumber": "POL001000000",
  "policyVersion": 0,             "correlationId": "<X-Correlation-Id or generated>",
  "data": { … }
}
```

`policyVersion` is `pas.policies.version` (optimistic lock) after the change committed.
`data` carries identifiers and business facts only; no policyholder PII and no free text.

## Events

| Event | Topic | Replaces | Emitted when | `data` |
| --- | --- | --- | --- | --- |
| `PolicyCreated` | `pas.policy.events.v1` | POLNEW `WRITEQ TD ACME.PAS.UNDERWRITING.REQUEST` (whole POLICY-RECORD) | `POST /api/v1/policies` commits | policyType, policyStatus, uwStatus, policyholderId, agentCode, branchCode, effective/expiry/inception dates, coverageLimit, deductible, totalPremium, coverages[sequenceNum, coverageType] |
| `UnderwritingReferred` | `pas.underwriting.events.v1` | UNDWRT `WRITEQ TD ACME.PAS.UNDERWRITING.DECISION` | a decision (automated or override) with code `RS` or `RM` | decisionId, decisionDate, decisionCode, riskScore, policyRiskScore, decisionReason, underwriterId, override, overrideBy |
| `UnderwritingDecided` | `pas.underwriting.events.v1` | same TDQ | a decision with code `AP` or `DC` | same as above |
| `PolicyRenewed` | `pas.policy.events.v1` | POLRNW renewal (no queue write; POLRCONF record) | AB-526 renewal commits, via `PolicyEventPublisher.policyRenewed` | policyType, policyStatus, uwStatus, previousExpiryDate, effectiveDate, expiryDate, renewalCount, previousPremium, totalPremium, rateChangePct |
| `PolicyEndorsed` | `pas.policy.events.v1` | POLEND `WRITEQ TS ENDORSEMENT-AUDIT` | AB-526 endorsement commits, via `PolicyEventPublisher.policyEndorsed` | endorsementSeq, endorsementType, endorsementEffectiveDate, premiumAdjustment, totalPremium, policyStatus, effectiveDate, expiryDate |
| `DocumentRequested` | `pas.document.requests.v1` | MQ event that triggers FileNet document creation | with every `PolicyCreated`, `PolicyRenewed`, `PolicyEndorsed` (same transaction) | documentTrigger (`NEW_BUSINESS`/`RENEWAL`/`ENDORSEMENT`), sourceTransaction (`POLNEW`/`POLRNW`/`POLEND`), triggeringEventId, triggeringEventType |

## Schemas and compatibility

- One JSON Schema (draft 2020-12) per event type in
  `pas-platform/pas-policy-service/src/main/resources/events/schemas/v1/<EventType>.schema.json`
  (envelope + `data`).
- `EventSchemaContractTest` validates the serialised envelope of every event type against its
  schema. `EventSchemaCompatibilityTest` compares each schema with the frozen copy in
  `src/test/resources/events/schema-baseline/v1/` and fails on removed properties, type/format/
  const changes, removed enum values, or a change to `required`. Adding an optional property is
  allowed; anything else needs `v2` schemas on `*.v2` topics, published alongside v1 until every
  consumer moves.

## Legacy queue bridges (transitional, ADR 0001 / ADR 0003)

| Consumer group | Topic | Events | Legacy queue (config) | Message |
| --- | --- | --- | --- | --- |
| `pas-filenet-document-bridge` | `pas.document.requests.v1` | `DocumentRequested` | `pas.integration.filenet-queue` (`PAS_FILENET_QUEUE`; real name Pending: Document/FileNet team) | 125-byte POLICY-RECORD in the POLNEW/POLRNW/POLEND layout named by `sourceTransaction` |
| `pas-uw-request-bridge` | `pas.policy.events.v1` | `PolicyCreated` | `ACME.PAS.UNDERWRITING.REQUEST` | POLNEW layout |
| `pas-uw-decision-bridge` | `pas.underwriting.events.v1` | `UnderwritingDecided`, `UnderwritingReferred` | `ACME.PAS.UNDERWRITING.DECISION` | UNDWRT layout |

- The bridges live in `pas-policy-service` (package `policy.integration`) and are **off** unless
  `pas.integration.bridges-enabled=true` (`PAS_INTEGRATION_BRIDGES_ENABLED`).
- `LegacyPolicyMessages` rebuilds the message from `pas.policy_versions` at the event's
  `policyVersion`, in IBM037, with exactly the fields each CICS program had populated (golden
  tests: `parity/programs/{polnew,polrnw,polend,undwrt}-msg`, `LegacyMessageParityTest`).
- Transport: `LegacyQueueTransport`. `pas.integration.transport=logging` (default, logs metadata
  only) or `memory` (test environments; `GET /api/v1/admin/legacy-messages`). The IBM MQ/JMS
  transport is **Pending: Document/FileNet team** and **Pending: Underwriting team**; nothing is
  delivered to FileNet today.
- Idempotency: each consumer claims `(consumer, eventId)` in `pas.processed_events` in the same
  transaction as the send; a duplicate or replayed event is skipped. Delivery to the queue is
  at least once (a crash between send and commit resends).

## Dead letters and replay

- Failures are retried with exponential backoff (`pas.integration.max-retries` 3,
  `retry-initial-interval` PT1S, `retry-multiplier` 2, `retry-max-interval` PT30S), then the
  record goes to `<topic>.dlt` with Spring Kafka's `kafka_dlt-*` headers. Records that cannot be
  parsed as a v1 envelope (`PoisonMessageException`) skip the retries.
- `pas-dead-letter-recorder` parks every DLT record in `pas.dead_letters` (status `PARKED`).
- Operators (scope `batch:operate`):
  - `GET /api/v1/admin/dead-letters?status=PARKED` — list; `GET /api/v1/admin/dead-letters/{id}`
    — one, with the payload.
  - `POST /api/v1/admin/dead-letters/{id}/replay` — republish the original key, value and
    `eventId`/`eventType` headers to the original topic (header `pasReplayOfDeadLetter`), status
    `REPLAYED`, `replay_count`+1, `replayed_by` = JWT subject. Fix the cause first; replaying an
    already-processed event is a no-op thanks to the eventId check.
- Evidence: `LegacyQueueBridgeIT` (Testcontainers Kafka + PostgreSQL): missing version →
  retries → DLT → parked → replay → processed once; poison record → DLT without retries.

Notes:

- The ADR lists `PolicyIssued` for the policy topic. POLNEW creates a *pending* (`PN`) policy
  that still needs underwriting, so the event is named `PolicyCreated`; `PolicyIssued` stays
  reserved for binding/activation. AB-528 confirms these names (Pending: consumer sign-off).
- `riskScore` is the 4-digit `WS-RISK-SCORE` that UNDWRT writes to DB2; `policyRiskScore` is
  the `PIC 9(3)` value (score mod 1000) UNDWRT put on the POLICY-RECORD it sent to the TDQ. The
  AB-528 bridge applies the same `PIC 9(3)` truncation when it rebuilds POLICY-RECORD.
- Overrides carry `overrideBy` (JWT subject) but not the free-text reason; read it from
  `GET /api/v1/policies/{n}/underwriting-decisions`.
