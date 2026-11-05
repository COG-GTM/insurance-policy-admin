package com.acme.insurance.pas.persistence;

import java.time.LocalDateTime;

/**
 * PASAPP.EVENT_OUTBOX row (V50). {@code outboxSeq} orders the relay; {@code eventId} is the
 * consumers' idempotency key.
 */
public record OutboxRow(
    Long outboxSeq,
    String eventId,
    String eventType,
    String topic,
    String messageKey,
    String payload,
    LocalDateTime createdAt,
    LocalDateTime publishedAt,
    int attempts,
    String lastError) {

  /** A new row to append. */
  public static OutboxRow pending(
      String eventId, String eventType, String topic, String messageKey, String payload) {
    return new OutboxRow(null, eventId, eventType, topic, messageKey, payload, null, null, 0, null);
  }
}
