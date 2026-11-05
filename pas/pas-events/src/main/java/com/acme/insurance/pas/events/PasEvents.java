package com.acme.insurance.pas.events;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builds the events and their outbox messages. The envelope is {@code {eventId, eventType,
 * schemaVersion, policyNumber, occurredAt, correlationId, data}}; dates are ISO-8601 and money is a
 * plain decimal number with the DB2 scale.
 */
public final class PasEvents {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
          .build();

  private final Clock clock;
  private final Supplier<UUID> ids;

  public PasEvents(Clock clock) {
    this(clock, UUID::randomUUID);
  }

  public PasEvents(Clock clock, Supplier<UUID> ids) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public PasEvent policyCreated(Policy policy, List<Coverage> coverages, String correlationId) {
    return event(
        PasEventType.POLICY_CREATED,
        policy.policyNumber(),
        correlationId,
        PolicyCreated.of(policy, coverages));
  }

  public PasEvent underwritingRequested(Policy policy, String correlationId) {
    return event(
        PasEventType.UNDERWRITING_REQUESTED,
        policy.policyNumber(),
        correlationId,
        UnderwritingRequested.of(policy));
  }

  public PasEvent underwritingDecided(UnderwritingDecision decision, String correlationId) {
    return event(
        PasEventType.UNDERWRITING_DECIDED,
        decision.policyNumber(),
        correlationId,
        UnderwritingDecided.of(decision));
  }

  private PasEvent event(
      PasEventType type, String policyNumber, String correlationId, Object data) {
    return new PasEvent(
        ids.get(),
        type,
        PasEvent.SCHEMA_VERSION,
        policyNumber,
        clock.instant(),
        correlationId,
        data);
  }

  /** The JSON envelope of {@code event}. */
  public static String toJson(PasEvent event) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", event.eventId().toString());
    envelope.put("eventType", event.type().topic());
    envelope.put("schemaVersion", event.schemaVersion());
    envelope.put("policyNumber", event.policyNumber());
    envelope.put("occurredAt", event.occurredAt());
    envelope.put("correlationId", event.correlationId());
    envelope.put("data", event.data());
    try {
      return JSON.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("event cannot be serialised: " + event.type(), e);
    }
  }

  public static OutboxMessage toOutboxMessage(PasEvent event) {
    return new OutboxMessage(
        event.eventId().toString(),
        event.type().topic(),
        event.type().topic(),
        event.policyNumber(),
        toJson(event));
  }
}
