package com.acme.insurance.pas.policy.integration;

import com.acme.insurance.pas.common.domain.PolicyRecord;
import com.acme.insurance.pas.policy.events.PolicyVersionSnapshots;
import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one consumed event into one legacy queue message, at most once per (consumer, eventId):
 * claim the eventId, rebuild {@code POLICY-RECORD} at the event's {@code policyVersion}, send.
 * Delivery to the queue is at-least-once: if the send succeeds but the commit fails the event is
 * retried and the message may be sent again (the legacy queues never had exactly-once either).
 */
public class LegacyMessageHandler {

  private final ProcessedEvents processed;
  private final PolicyVersionSnapshots snapshots;
  private final LegacyQueueTransport transport;
  private final Clock clock;

  public LegacyMessageHandler(
      ProcessedEvents processed,
      PolicyVersionSnapshots snapshots,
      LegacyQueueTransport transport,
      Clock clock) {
    this.processed = processed;
    this.snapshots = snapshots;
    this.transport = transport;
    this.clock = clock;
  }

  /** Returns false when the event was already processed by {@code consumer}. */
  @Transactional
  public boolean handle(
      String consumer, String queue, String topic, ParsedEvent event, LegacyMessageSource source) {
    if (!processed.claim(consumer, event.eventId(), topic)) {
      return false;
    }
    PolicyRecord row =
        snapshots
            .find(event.policyNumber(), event.policyVersion())
            .orElseThrow(
                () ->
                    new PolicyVersionNotFoundException(
                        event.policyNumber(), event.policyVersion()));
    byte[] message = LegacyPolicyMessages.encode(source, row);
    transport.send(LegacyQueueMessage.of(queue, consumer, event, source, message, clock.instant()));
    return true;
  }
}
