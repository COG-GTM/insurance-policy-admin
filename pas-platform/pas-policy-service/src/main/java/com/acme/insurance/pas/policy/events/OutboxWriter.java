package com.acme.insurance.pas.policy.events;

import com.acme.insurance.pas.policy.support.BusinessCalendar;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends events to {@code pas.outbox} inside the caller's transaction, so an event exists if and
 * only if the state change it describes committed. {@link OutboxRelay} publishes them.
 */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class OutboxWriter {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final BusinessCalendar calendar;

  public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, BusinessCalendar calendar) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.calendar = calendar;
  }

  /** Writes one event keyed by policy number (per-policy ordering on the topic). */
  @Transactional(propagation = Propagation.MANDATORY)
  public PasEvent append(
      String topic,
      String eventType,
      String policyNumber,
      long policyVersion,
      String correlationId,
      Object data) {
    PasEvent event =
        new PasEvent(
            UUID.randomUUID(),
            eventType,
            PasEventTypes.SCHEMA_VERSION,
            calendar.now(),
            policyNumber,
            policyVersion,
            correlationId,
            data);
    String payload;
    try {
      payload = mapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialise " + eventType, e);
    }
    jdbc.update(
        "INSERT INTO pas.outbox (event_id, topic, message_key, event_type, payload)"
            + " VALUES (?, ?, ?, ?, ?)",
        event.eventId(),
        topic,
        policyNumber,
        eventType,
        payload);
    return event;
  }
}
