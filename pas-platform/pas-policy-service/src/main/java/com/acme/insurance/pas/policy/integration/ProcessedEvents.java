package com.acme.insurance.pas.policy.integration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@code pas.processed_events} (V9): idempotent consumers keyed on (consumer, eventId). */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class ProcessedEvents {

  private final JdbcTemplate jdbc;

  public ProcessedEvents(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Claims {@code eventId} for {@code consumer} in the caller's transaction. False when it was
   * already processed; a rollback releases the claim so the event is retried.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean claim(String consumer, UUID eventId, String topic) {
    return jdbc.update(
            "INSERT INTO pas.processed_events (consumer, event_id, topic) VALUES (?, ?, ?)"
                + " ON CONFLICT (consumer, event_id) DO NOTHING",
            consumer,
            eventId,
            topic)
        == 1;
  }

  public int count(String consumer, UUID eventId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM pas.processed_events WHERE consumer = ? AND event_id = ?",
            Integer.class,
            consumer,
            eventId);
    return n == null ? 0 : n;
  }
}
