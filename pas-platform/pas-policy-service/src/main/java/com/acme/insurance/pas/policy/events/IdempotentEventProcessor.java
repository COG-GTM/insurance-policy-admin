package com.acme.insurance.pas.policy.events;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdempotentEventProcessor {
  private final JdbcClient jdbc;
  private final EventEnvelopes envelopes;
  private final MeterRegistry metrics;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Collaborators are Spring-managed services.")
  public IdempotentEventProcessor(
      JdbcClient jdbc, EventEnvelopes envelopes, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.envelopes = envelopes;
    this.metrics = metrics;
  }

  @Transactional
  public void process(
      String group,
      String topic,
      String rawValue,
      VersionPolicy versionPolicy,
      Consumer<EventEnvelope> handler) {
    Objects.requireNonNull(versionPolicy, "versionPolicy");
    EventEnvelope event;
    try {
      event = envelopes.parse(rawValue);
    } catch (RuntimeException exception) {
      metrics
          .counter("pas.events.consumed", "topic", topic, "group", group, "result", "failed")
          .increment();
      throw exception;
    }
    int inserted =
        jdbc.sql(
                "INSERT INTO pas.consumed_events (consumer_group, event_id, topic, policy_number, policy_version, result) VALUES (:group, :eventId, :topic, :policyNumber, :policyVersion, 'PROCESSING') ON CONFLICT DO NOTHING")
            .param("group", group)
            .param("eventId", event.eventId())
            .param("topic", topic)
            .param("policyNumber", event.policyNumber())
            .param("policyVersion", event.policyVersion())
            .update();
    if (inserted == 0) {
      metrics
          .counter("pas.events.consumed", "topic", topic, "group", group, "result", "duplicate")
          .increment();
      return;
    }
    if (versionPolicy == VersionPolicy.MONOTONIC) {
      Long current =
          jdbc.sql(
                  "SELECT last_policy_version FROM pas.consumer_policy_versions WHERE consumer_group = :group AND policy_number = :policyNumber")
              .param("group", group)
              .param("policyNumber", event.policyNumber())
              .query(Long.class)
              .optional()
              .orElse(null);
      if (current != null && current >= event.policyVersion()) {
        mark(group, event, "STALE");
        metrics
            .counter("pas.events.consumed", "topic", topic, "group", group, "result", "stale")
            .increment();
        return;
      }
    }
    try {
      handler.accept(event);
    } catch (SkippedEventException exception) {
      mark(group, event, "SKIPPED");
      metrics
          .counter("pas.events.consumed", "topic", topic, "group", group, "result", "skipped")
          .increment();
      return;
    } catch (RuntimeException exception) {
      metrics
          .counter("pas.events.consumed", "topic", topic, "group", group, "result", "failed")
          .increment();
      throw exception;
    }
    if (versionPolicy == VersionPolicy.MONOTONIC) {
      jdbc.sql(
              "INSERT INTO pas.consumer_policy_versions (consumer_group, policy_number, last_policy_version) VALUES (:group, :policyNumber, :version) ON CONFLICT (consumer_group, policy_number) DO UPDATE SET last_policy_version = EXCLUDED.last_policy_version, updated_at = now() WHERE pas.consumer_policy_versions.last_policy_version < EXCLUDED.last_policy_version")
          .param("group", group)
          .param("policyNumber", event.policyNumber())
          .param("version", event.policyVersion())
          .update();
    }
    mark(group, event, "PROCESSED");
    metrics
        .counter("pas.events.consumed", "topic", topic, "group", group, "result", "processed")
        .increment();
  }

  private void mark(String group, EventEnvelope event, String result) {
    jdbc.sql(
            "UPDATE pas.consumed_events SET result = :result, consumed_at = now() WHERE consumer_group = :group AND event_id = :eventId")
        .param("result", result)
        .param("group", group)
        .param("eventId", event.eventId())
        .update();
  }

  public static final class SkippedEventException extends RuntimeException {}
}
