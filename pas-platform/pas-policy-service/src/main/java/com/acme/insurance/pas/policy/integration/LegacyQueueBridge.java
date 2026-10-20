package com.acme.insurance.pas.policy.integration;

import com.acme.insurance.pas.policy.events.PasEventTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Kafka → legacy queue bridges (AB-528, ADR 0001 transitional Kafka→MQ bridge), hosted in
 * pas-policy-service so no new deployable is needed:
 *
 * <ul>
 *   <li>{@code pas.document.requests.v1} {@code DocumentRequested} → FileNet document trigger
 *       (POLNEW/POLRNW/POLEND layout). Owner of the other end: Pending: Document/FileNet team.
 *   <li>{@code pas.policy.events.v1} {@code PolicyCreated} → {@code ACME.PAS.UNDERWRITING.REQUEST}
 *       (POLNEW layout). Pending: Underwriting team.
 *   <li>{@code pas.underwriting.events.v1} decisions/referrals → {@code
 *       ACME.PAS.UNDERWRITING.DECISION} (UNDWRT layout). Pending: Underwriting team.
 * </ul>
 *
 * Failures retry with exponential backoff, then go to {@code <topic>.dlt}; poison records go there
 * at once ({@link IntegrationConfiguration}).
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class LegacyQueueBridge {

  public static final String FILENET_CONSUMER = "filenet-document-bridge";
  public static final String UW_REQUEST_CONSUMER = "uw-request-bridge";
  public static final String UW_DECISION_CONSUMER = "uw-decision-bridge";

  private static final Logger log = LoggerFactory.getLogger(LegacyQueueBridge.class);
  private static final Set<String> DOCUMENT_SOURCES = Set.of("POLNEW", "POLRNW", "POLEND");

  private final ObjectMapper mapper;
  private final LegacyMessageHandler handler;
  private final IntegrationProperties properties;

  public LegacyQueueBridge(
      ObjectMapper mapper, LegacyMessageHandler handler, IntegrationProperties properties) {
    this.mapper = mapper;
    this.handler = handler;
    this.properties = properties;
  }

  @KafkaListener(
      id = FILENET_CONSUMER,
      groupId = "pas-" + FILENET_CONSUMER,
      topics = "${pas.events.document-topic}",
      containerFactory = IntegrationConfiguration.BRIDGE_FACTORY)
  public void onDocumentRequest(ConsumerRecord<String, String> record) {
    ParsedEvent event = ParsedEvent.parse(mapper, record.value());
    if (!PasEventTypes.DOCUMENT_REQUESTED.equals(event.eventType())) {
      throw new PoisonMessageException("unexpected eventType " + event.eventType());
    }
    String source = event.data().path("sourceTransaction").asText();
    if (!DOCUMENT_SOURCES.contains(source)) {
      throw new PoisonMessageException("unknown sourceTransaction '" + source + "'");
    }
    deliver(
        FILENET_CONSUMER,
        properties.filenetQueue(),
        record,
        event,
        LegacyMessageSource.valueOf(source));
  }

  @KafkaListener(
      id = UW_REQUEST_CONSUMER,
      groupId = "pas-" + UW_REQUEST_CONSUMER,
      topics = "${pas.events.policy-topic}",
      containerFactory = IntegrationConfiguration.BRIDGE_FACTORY)
  public void onPolicyEvent(ConsumerRecord<String, String> record) {
    ParsedEvent event = ParsedEvent.parse(mapper, record.value());
    if (PasEventTypes.POLICY_CREATED.equals(event.eventType())) {
      deliver(
          UW_REQUEST_CONSUMER,
          properties.underwritingRequestQueue(),
          record,
          event,
          LegacyMessageSource.POLNEW);
    }
  }

  @KafkaListener(
      id = UW_DECISION_CONSUMER,
      groupId = "pas-" + UW_DECISION_CONSUMER,
      topics = "${pas.events.underwriting-topic}",
      containerFactory = IntegrationConfiguration.BRIDGE_FACTORY)
  public void onUnderwritingEvent(ConsumerRecord<String, String> record) {
    ParsedEvent event = ParsedEvent.parse(mapper, record.value());
    if (PasEventTypes.UNDERWRITING_DECIDED.equals(event.eventType())
        || PasEventTypes.UNDERWRITING_REFERRED.equals(event.eventType())) {
      deliver(
          UW_DECISION_CONSUMER,
          properties.underwritingDecisionQueue(),
          record,
          event,
          LegacyMessageSource.UNDWRT);
    }
  }

  private void deliver(
      String consumer,
      String queue,
      ConsumerRecord<String, String> record,
      ParsedEvent event,
      LegacyMessageSource source) {
    boolean sent = handler.handle(consumer, queue, record.topic(), event, source);
    log.info(
        "{} eventId={} policy={} version={} -> {} ({})",
        consumer,
        event.eventId(),
        event.policyNumber(),
        event.policyVersion(),
        queue,
        sent ? "sent" : "duplicate, skipped");
  }
}
