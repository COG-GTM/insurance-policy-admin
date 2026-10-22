package com.acme.insurance.pas.policy.events;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope(
    UUID eventId,
    String eventType,
    int schemaVersion,
    String policyNumber,
    long policyVersion,
    OffsetDateTime occurredAt,
    String correlationId,
    Map<String, Object> data) {
  public EventEnvelope {
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }

  @Override
  public Map<String, Object> data() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
