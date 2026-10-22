package com.acme.insurance.pas.policy.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EventEnvelopes {
  private final ObjectMapper mapper;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "ObjectMapper is a Spring-managed collaborator.")
  public EventEnvelopes(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public EventEnvelope parse(String raw) {
    try {
      Map<?, ?> values = mapper.readValue(raw, Map.class);
      UUID eventId = UUID.fromString(requiredText(values, "eventId"));
      String eventType = requiredText(values, "eventType");
      Object version = values.get("schemaVersion");
      if (!(version instanceof Number number) || number.intValue() != 1) {
        throw new PoisonEventException("Unsupported schemaVersion");
      }
      String policyNumber = requiredText(values, "policyNumber");
      Object policyVersion = values.get("policyVersion");
      if (!(policyVersion instanceof Number policyVersionNumber)
          || policyVersionNumber.longValue() < 1) {
        throw new PoisonEventException("Invalid policyVersion");
      }
      String occurredAt = requiredText(values, "occurredAt");
      String correlationId = requiredText(values, "correlationId");
      Object data = values.get("data");
      if (!(data instanceof Map<?, ?> eventData)) {
        throw new PoisonEventException("Missing event data");
      }
      return new EventEnvelope(
          eventId,
          eventType,
          1,
          policyNumber,
          ((Number) policyVersion).longValue(),
          java.time.OffsetDateTime.parse(occurredAt),
          correlationId,
          mapper.convertValue(
              eventData,
              mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
    } catch (PoisonEventException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new PoisonEventException("Invalid event envelope", exception);
    }
  }

  private static String requiredText(Map<?, ?> values, String field) {
    Object value = values.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new PoisonEventException("Missing " + field);
    }
    return text;
  }
}
