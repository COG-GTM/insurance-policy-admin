package com.acme.insurance.pas.policy.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWriter {
  public static final String POLICY_TOPIC = "pas.policy.events.v1";
  public static final String UNDERWRITING_TOPIC = "pas.underwriting.events.v1";
  public static final String DOCUMENT_TOPIC = "pas.document.requests.v1";

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "ObjectMapper is a Spring-managed serialization dependency.")
  public OutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper, Clock clock) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(
      String topic,
      String eventType,
      String policyNumber,
      long policyVersion,
      String correlationId,
      Object data) {
    UUID eventId = UUID.randomUUID();
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId);
    envelope.put("eventType", eventType);
    envelope.put("schemaVersion", 1);
    envelope.put("policyNumber", policyNumber.trim());
    envelope.put("policyVersion", policyVersion);
    envelope.put("occurredAt", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    envelope.put("correlationId", correlationId);
    envelope.put("data", data);
    jdbc.sql(
            """
            INSERT INTO pas.outbox (
                aggregate_type, aggregate_id, topic, event_type, event_id, payload
            ) VALUES (
                'POLICY', :policyNumber, :topic, :eventType, :eventId, CAST(:payload AS jsonb)
            )
            """)
        .param("policyNumber", policyNumber.trim())
        .param("topic", topic)
        .param("eventType", eventType)
        .param("eventId", eventId)
        .param("payload", toJson(envelope))
        .update();
    return eventId;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize outbox event", exception);
    }
  }
}
