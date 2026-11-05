package com.acme.insurance.pas.events;

import java.util.Optional;

/**
 * Event types, their Kafka topics (ADR 0001 D4, component MSG-01) and the legacy TD queue each one
 * replaces. The request and decision queues resolve to the same four-character TD queue on CICS
 * (quirk Q-13); the topics keep them apart.
 */
public enum PasEventType {
  /** A new policy was created (POLNEW). New: no TD queue carried it. */
  POLICY_CREATED("pas.policy.created", null),
  /** A new policy waits for underwriting (POLNEW {@code 7000-SEND-MQ-MESSAGE}). */
  UNDERWRITING_REQUESTED("pas.underwriting.requested", "ACME.PAS.UNDERWRITING.REQUEST"),
  /** An underwriting decision or override (UNDWRT {@code 7000-SEND-MQ-NOTIFICATION}). */
  UNDERWRITING_DECIDED("pas.underwriting.decided", "ACME.PAS.UNDERWRITING.DECISION");

  private final String topic;
  private final String legacyQueue;

  PasEventType(String topic, String legacyQueue) {
    this.topic = topic;
    this.legacyQueue = legacyQueue;
  }

  /** Topic name; also the {@code eventType} of the envelope. */
  public String topic() {
    return topic;
  }

  /** The WS-MQ-QUEUE value of the COBOL write this event replaces, if any. */
  public Optional<String> legacyQueue() {
    return Optional.ofNullable(legacyQueue);
  }

  public static Optional<PasEventType> fromTopic(String topic) {
    for (PasEventType type : values()) {
      if (type.topic.equals(topic)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }
}
