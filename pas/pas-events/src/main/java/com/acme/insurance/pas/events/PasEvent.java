package com.acme.insurance.pas.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One domain event: envelope fields plus a typed payload. {@code eventId} is the idempotency key
 * consumers deduplicate on (delivery is at least once); {@code policyNumber} is the message key, so
 * events of one policy stay in order on a partition.
 */
public record PasEvent(
    UUID eventId,
    PasEventType type,
    int schemaVersion,
    String policyNumber,
    Instant occurredAt,
    String correlationId,
    Object data) {

  public static final int SCHEMA_VERSION = 1;

  public PasEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(policyNumber, "policyNumber");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(data, "data");
  }
}
