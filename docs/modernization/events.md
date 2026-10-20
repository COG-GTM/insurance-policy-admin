# PAS domain events (AB-525)

pas-policy-service publishes domain events to Kafka through a transactional outbox
(ADR 0001 D4). This catalogue lists the events implemented so far; AB-526/AB-528 add the
rest. Final topic and event names are confirmed in AB-528 (ADR 0001 D4).

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
  `pas.underwriting.events.v1`), `pas.outbox.relay.enabled` (`PAS_OUTBOX_RELAY_ENABLED`),
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

Notes:

- The ADR lists `PolicyIssued` for the policy topic. POLNEW creates a *pending* (`PN`) policy
  that still needs underwriting, so the event is named `PolicyCreated`; `PolicyIssued` stays
  reserved for binding/activation. AB-528 confirms the names.
- `riskScore` is the 4-digit `WS-RISK-SCORE` that UNDWRT writes to DB2; `policyRiskScore` is
  the `PIC 9(3)` value (score mod 1000) UNDWRT put on the POLICY-RECORD it sent to the TDQ. The
  AB-528 FileNet bridge must use `policyRiskScore` when it rebuilds POLICY-RECORD.
- Overrides carry `overrideBy` (JWT subject) but not the free-text reason; read it from
  `GET /api/v1/policies/{n}/underwriting-decisions`.
