package com.acme.insurance.pas.policy.integration;

import java.time.Instant;
import java.util.HexFormat;

/**
 * One fixed-width message for a legacy queue consumer: the 125-byte {@code POLICY-RECORD} in host
 * encoding plus the event it was built from.
 */
public record LegacyQueueMessage(
    String queue,
    String consumer,
    String eventId,
    String eventType,
    String policyNumber,
    long policyVersion,
    String sourceTransaction,
    String correlationId,
    String payloadHex,
    int length,
    Instant sentAt) {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  public static LegacyQueueMessage of(
      String queue,
      String consumer,
      ParsedEvent event,
      LegacyMessageSource source,
      byte[] payload,
      Instant sentAt) {
    return new LegacyQueueMessage(
        queue,
        consumer,
        event.eventId().toString(),
        event.eventType(),
        event.policyNumber(),
        event.policyVersion(),
        source.name(),
        event.correlationId(),
        HEX.formatHex(payload),
        payload.length,
        sentAt);
  }

  /** The message bytes (a fresh copy). */
  public byte[] payload() {
    return HEX.parseHex(payloadHex);
  }
}
