# Kafka to IBM MQ sink (INT-01, AB-528)

`pas-policy-service`'s legacy bridge (`pas.bridge.enabled=true`) turns every domain event into the
125-byte EBCDIC POLICY-RECORD the legacy consumers parse today and publishes it, as raw bytes keyed by
policy number, to three bridge topics. These Kafka Connect configurations move those bytes unchanged
to the existing queues with the IBM MQ sink connector (`com.ibm.eventstreams.connect.mqsink`):

| Config | Bridge topic | Queue | Consumer |
|---|---|---|---|
| `mq-sink-underwriting-request.json` | `pas.bridge.mq.underwriting-request` | `ACME.PAS.UNDERWRITING.REQUEST` (I-4) | Underwriting work queue |
| `mq-sink-underwriting-decision.json` | `pas.bridge.mq.underwriting-decision` | `ACME.PAS.UNDERWRITING.DECISION` (I-5) | Underwriting ops / FileNet |
| `mq-sink-filenet-document-trigger.json` | `pas.bridge.filenet.document-trigger` | `PAS_FILENET_TRIGGER_QUEUE` (I-6, name pending FU-06) | FileNet document creation |

- `ByteArrayConverter` and `mq.message.body.jms=false` write the record as an MQ bytes message with
  no JMS envelope, as the CICS-to-MQ path does.
- `mq.kafka.headers.copy.to.jms.properties` copies `eventId`, `eventType`, `documentType`
  (`NEW_BUSINESS`, `RENEWAL`, `ENDORSEMENT`) and, for decisions, `decisionCode` / `referral` to
  message properties. Consumers that ignore properties see exactly today's message; `eventId` lets
  them drop the duplicates at-least-once delivery can produce (Q-528-1).
- Connection values come from the Connect worker environment and the MQ credentials from a
  `FileConfigProvider` file managed by the secrets manager (SEC-02); nothing is committed.
- Deploy per environment through the platform team's Connect cluster. `tools/retirement/messaging_decommission.py --check`
  validates these files against the bridge's default topics.

Native Kafka consumers (FileNet, underwriting referrals) subscribe to `pas.policy.*` and
`pas.underwriting.*` directly with the JSON schemas in `pas/pas-events/src/main/resources/schemas/`;
once every consumer has moved, the sink and the bridge are retired by
[`docs/pas/messaging-decommission.md`](../../../docs/pas/messaging-decommission.md).
