# New business and underwriting runbook (AB-525)

## API and authorization

| Operation | Route | Scope |
|---|---|---|
| Create policy | `POST /api/v1/policies` | `policy:write` |
| Run underwriting | `POST /api/v1/policies/{policyNumber}/underwriting-decisions` | `underwriting:decide` |
| List decisions | `GET /api/v1/policies/{policyNumber}/underwriting-decisions` | `policy:read` |
| Override latest decision | `POST /api/v1/policies/{policyNumber}/underwriting-decisions/{decisionId}/override` | `underwriting:override` |

Every operation also enforces `book`, `agent_id`, and `branch_codes` entitlements. Unentitled
policies are indistinguishable from missing policies. New-business creation accepts an optional
`Idempotency-Key`; reuse with the same request replays the stored response, and reuse with a
different request returns 409.

The `new-business` and `underwriting` route flags default to `legacy`. Legacy and retired routes
return 503; `shadow` records only PII-free comparison results without changing policy state; `new`
enables policy writes and underwriting decisions. Override is supported only in `new` mode.

## Claims history

Default mode is `rest`, using the `claims-api` OAuth2 client-credentials registration. Configure
`PAS_CLAIMS_REST_BASE_URL`, `PAS_CLAIMS_TOKEN_URL`, `PAS_CLAIMS_CLIENT_ID`, and
`PAS_CLAIMS_CLIENT_SECRET`. A 404 claims response is treated as zero claims; 5xx and I/O failures
are retried and ultimately fail closed with 503. Other 4xx responses are not retried. Local smoke
tests and automated tests select `PAS_CLAIMS_ADAPTER=fixture`.

## Outbox and topics

Policy creation and underwriting write events in the same database transaction as the policy and
audit changes. The relay publishes in outbox ID order to `pas.policy.events.v1` and
`pas.underwriting.events.v1`, and marks rows only after Kafka acknowledges. Each topic contains at
most one event for a policy version. A create emits `PolicyCreated` to the policy topic,
`DocumentRequested` with type `NEW_BUSINESS` to the document topic, and a
`UnderwritingReferralRequested` with trigger `NEW_BUSINESS` to the underwriting topic. A decision
or override with code RS/RM emits only `UnderwritingReferralRequested` with trigger `DECISION`;
its data contains the complete decision payload. AP/DC decisions emit only
`UnderwritingDecisionRecorded`. Topic creation is tuned with `PAS_KAFKA_TOPIC_PARTITIONS` and
`PAS_KAFKA_TOPIC_REPLICAS`; event schemas are under `pas-platform/events/schemas/v1/`. Event
payloads must remain PII-free.

The `pas_shadow` `request_hash` is a digest calculated after removing `policyholderId` from the
request; the stored hash contains no PII.

## Local verification

From the repository root, run `make dev` to build and start the local stack. The policy service
port is printed by Make; readiness is `/actuator/health/readiness`. The local profile selects the
claims fixture adapter and routes new business and underwriting to the new service. Mint a signed
development JWT:

```sh
cd pas-platform
./dev/dev-jwt.sh mint --scope "policy:write underwriting:decide underwriting:override policy:read" \
  --claim book=AGENT --claim agent_id=AGT001 --claim branch_codes=B001
```

Do not put JWTs, OAuth client secrets, or holder data in smoke-test transcripts.
