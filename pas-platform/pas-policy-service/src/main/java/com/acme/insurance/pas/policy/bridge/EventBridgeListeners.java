package com.acme.insurance.pas.policy.bridge;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import com.acme.insurance.pas.copybook.CopybookCharset;
import com.acme.insurance.pas.copybook.LegacyPolicyRecords;
import com.acme.insurance.pas.copybook.PolicyRecordCodec;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.policy.events.EventConsumerGroups;
import com.acme.insurance.pas.policy.events.EventEnvelope;
import com.acme.insurance.pas.policy.events.IdempotentEventProcessor;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.events.PoisonEventException;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.inquiry.PolicyInquiryRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Conditional;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Conditional(DocumentEventsNewCondition.class)
public class EventBridgeListeners {
  public static final String DOCUMENT_GROUP = EventConsumerGroups.DOCUMENT_BRIDGE;
  public static final String REFERRAL_GROUP = EventConsumerGroups.REFERRAL_BRIDGE;

  private final IdempotentEventProcessor processor;
  private final PolicyInquiryRepository inquiry;
  private final PolicyRepository policies;
  private final LegacyQueuePort queues;
  private final BridgeProperties properties;
  private final RouteFlags routeFlags;
  private final MeterRegistry metrics;
  private final CopybookCharset charset;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Collaborators are Spring-managed services and configuration.")
  public EventBridgeListeners(
      IdempotentEventProcessor processor,
      PolicyInquiryRepository inquiry,
      PolicyRepository policies,
      LegacyQueuePort queues,
      BridgeProperties properties,
      RouteFlags routeFlags,
      MeterRegistry metrics) {
    this.processor = processor;
    this.inquiry = inquiry;
    this.policies = policies;
    this.queues = queues;
    this.properties = properties;
    this.routeFlags = routeFlags;
    this.metrics = metrics;
    this.charset = CopybookCharset.fromName(properties.charset());
  }

  @KafkaListener(topics = OutboxWriter.DOCUMENT_TOPIC, groupId = DOCUMENT_GROUP)
  public void documents(String value) {
    requireNewRoute();
    processor.process(
        DOCUMENT_GROUP, OutboxWriter.DOCUMENT_TOPIC, value, event -> sendDocument(event));
  }

  @KafkaListener(topics = OutboxWriter.UNDERWRITING_TOPIC, groupId = REFERRAL_GROUP)
  public void referrals(String value) {
    requireNewRoute();
    processor.process(REFERRAL_GROUP, OutboxWriter.UNDERWRITING_TOPIC, value, this::sendReferral);
  }

  private void sendDocument(EventEnvelope event) {
    String documentType = text(event, "documentType");
    Policy policy = currentPolicy(event, DOCUMENT_GROUP);
    String queue;
    byte[] body;
    Map<String, String> propertiesForMessage;
    if ("UNDERWRITING_DECISION".equals(documentType)) {
      queue = properties.decisionQueue();
      body = LegacyPolicyRecords.undwrtDecisionRecord(policy, charset);
      propertiesForMessage = Map.of();
    } else {
      queue = properties.documentQueue();
      body = new PolicyRecordCodec(charset).encode(policy);
      propertiesForMessage =
          Map.of(
              "PasDocumentType", documentType,
              "PasEventId", event.eventId().toString(),
              "PasPolicyVersion", Long.toString(event.policyVersion()));
    }
    queues.put(queue, body, propertiesForMessage);
    metrics.counter("pas.bridge.messages", "queue", queue, "group", DOCUMENT_GROUP).increment();
  }

  private void sendReferral(EventEnvelope event) {
    if (!"UnderwritingReferralRequested".equals(event.eventType())
        || !properties.referralTriggers().contains(text(event, "trigger"))) {
      throw new IdempotentEventProcessor.SkippedEventException();
    }
    Policy policy = currentPolicy(event, REFERRAL_GROUP);
    queues.put(
        properties.referralQueue(),
        LegacyPolicyRecords.polnewReferralRecord(policy, charset),
        Map.of());
    metrics
        .counter(
            "pas.bridge.messages", "queue", properties.referralQueue(), "group", REFERRAL_GROUP)
        .increment();
  }

  private Policy currentPolicy(EventEnvelope event, String group) {
    long currentVersion = policies.currentVersion(event.policyNumber());
    if (currentVersion < event.policyVersion()) {
      throw new IllegalStateException("Policy version has not caught up with event");
    }
    if (currentVersion > event.policyVersion()) {
      metrics.counter("pas.bridge.version.mismatch", "group", group).increment();
    }
    return inquiry
        .findPolicy(event.policyNumber(), new CallerEntitlements(true, null, List.of()))
        .orElseThrow(() -> new PoisonEventException("Policy not found"));
  }

  private void requireNewRoute() {
    if (routeFlags.state(RouteFlags.DOCUMENT_EVENTS) != RouteState.NEW) {
      throw new IllegalStateException("Document bridge route is not NEW");
    }
  }

  private static String text(EventEnvelope event, String key) {
    Object value = event.data().get(key);
    return value instanceof String text ? text : "";
  }
}
