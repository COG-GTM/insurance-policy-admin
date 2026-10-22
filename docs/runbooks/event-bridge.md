# Event bridge runbook

`pas.policy.events.v1` contains policy lifecycle events, `pas.underwriting.events.v1` contains
underwriting events, and `pas.document.requests.v1` contains document requests. The transactional
outbox relay publishes each event after commit.

The document consumer group is `pas-filenet-document-bridge`; the referral group is
`pas-uw-referral-bridge`. Their dead-letter topics are
`pas.document.requests.v1.pas-filenet-document-bridge.dlt` and
`pas.underwriting.events.v1.pas-uw-referral-bridge.dlt`. Poison envelopes are not retried;
other failures use three attempts with bounded exponential backoff.

Set `PAS_ROUTE_DOCUMENT_EVENTS=new` only after IBM MQ and its three queue names are configured.
`PAS_BRIDGE_MQ_HOST`, `PAS_BRIDGE_MQ_PORT`, `PAS_BRIDGE_MQ_QUEUE_MANAGER`,
`PAS_BRIDGE_MQ_CHANNEL`, `PAS_BRIDGE_MQ_TLS_CIPHER_SUITE`, `PAS_BRIDGE_MQ_USER`, and
`PAS_BRIDGE_MQ_PASSWORD` configure the MQ connection. `PAS_BRIDGE_CHARSET` defaults to `IBM037`;
`PAS_BRIDGE_REFERRAL_TRIGGERS` defaults to `NEW_BUSINESS`. Current policy rows are used if they
have advanced beyond the event version; a lagging policy row is retried. When the MQ host is set,
`PAS_BRIDGE_MQ_TLS_CIPHER_SUITE` is required; the service refuses to start with plaintext MQ. Outside
the `local` and `test` profiles, the suite is also required whenever `document-events` is `new`.

Replay an inclusive offset range with a JWT minted by `dev-jwt.sh` carrying `events:replay`:

```sh
curl -X POST "$PAS_BASE_URL/api/v1/admin/events/dlt-replay" \
  -H "Authorization: Bearer $DEV_JWT" -H "Content-Type: application/json" \
  -d '{"dltTopic":"pas.document.requests.v1.pas-filenet-document-bridge.dlt","partition":0,"fromOffset":0,"toOffset":10}'
```

Replay republishes key and payload to the original topic and does not commit source offsets.
Delivery is at least once: a send acknowledged by MQ followed by a database rollback can produce
a duplicate on redelivery. Consumers must remain idempotent. Logs contain actor and offset counts,
never event payloads.

Replay is an explicit operator action. These bridges are discrete-request consumers and use
`EVENT_ID_ONLY`: they deduplicate by event ID, process older events with unseen IDs, and do not
read or write a policy-version watermark. The ADR 0001 D6 version rule applies to state-replicating
consumers using `MONOTONIC`; replaying these bridge events does not change that policy.
