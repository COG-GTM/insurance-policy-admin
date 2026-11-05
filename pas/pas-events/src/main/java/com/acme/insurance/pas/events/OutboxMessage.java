package com.acme.insurance.pas.events;

import java.util.Objects;

/**
 * What goes into the outbox for one event and what the relay publishes: topic, message key (the
 * policy number), the event id (sent as a header and used for deduplication) and the JSON envelope.
 */
public record OutboxMessage(
    String eventId, String eventType, String topic, String key, String payload) {

  /** Maximum envelope length; PASAPP.EVENT_OUTBOX.PAYLOAD is VARCHAR(16000). */
  public static final int MAX_PAYLOAD_LENGTH = 16000;

  public OutboxMessage {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(payload, "payload");
    if (payload.length() > MAX_PAYLOAD_LENGTH) {
      throw new IllegalArgumentException("event payload exceeds " + MAX_PAYLOAD_LENGTH + " chars");
    }
  }
}
