package com.acme.insurance.pas.policy.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdempotentEventProcessorTest {
  private static final String GROUP = EventConsumerGroups.DOCUMENT_BRIDGE;
  private static final String TOPIC = OutboxWriter.DOCUMENT_TOPIC;
  private static final String POLICY_NUMBER = "POL000000001";

  @Test
  void monotonicPolicyDropsStaleEventsAndOnlyRaisesWatermark() {
    AtomicReference<Long> watermark = new AtomicReference<>(2L);
    AtomicReference<Long> pendingVersion = new AtomicReference<>();
    List<String> statements = new ArrayList<>();
    AtomicReference<String> lastResult = new AtomicReference<>();
    JdbcClient jdbc =
        jdbc(
            statements,
            sql -> {
              JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
              when(statement.param(anyString(), any()))
                  .thenAnswer(
                      invocation -> {
                        String name = invocation.getArgument(0);
                        Object value = invocation.getArgument(1);
                        if (sql.startsWith("INSERT INTO pas.consumer_policy_versions")
                            && name.equals("version")) {
                          pendingVersion.set(((Number) value).longValue());
                        }
                        if (sql.startsWith("UPDATE pas.consumed_events") && name.equals("result")) {
                          lastResult.set((String) value);
                        }
                        return statement;
                      });
              when(statement.update())
                  .thenAnswer(
                      invocation -> {
                        if (sql.startsWith("INSERT INTO pas.consumer_policy_versions")) {
                          watermark.updateAndGet(
                              current ->
                                  current == null
                                      ? pendingVersion.get()
                                      : Math.max(current, pendingVersion.get()));
                        }
                        return 1;
                      });
              JdbcClient.MappedQuerySpec<Long> query = mock(JdbcClient.MappedQuerySpec.class);
              when(statement.query(Long.class)).thenReturn(query);
              when(query.optional()).thenAnswer(invocation -> Optional.ofNullable(watermark.get()));
              return statement;
            });
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    IdempotentEventProcessor processor = processor(jdbc, metrics);
    List<Long> handledVersions = new ArrayList<>();

    processor.process(
        GROUP,
        TOPIC,
        event("00000000-0000-0000-0000-000000000001", 1),
        VersionPolicy.MONOTONIC,
        envelope -> handledVersions.add(envelope.policyVersion()));
    processor.process(
        GROUP,
        TOPIC,
        event("00000000-0000-0000-0000-000000000002", 3),
        VersionPolicy.MONOTONIC,
        envelope -> handledVersions.add(envelope.policyVersion()));
    processor.process(
        GROUP,
        TOPIC,
        event("00000000-0000-0000-0000-000000000003", 2),
        VersionPolicy.MONOTONIC,
        envelope -> handledVersions.add(envelope.policyVersion()));

    assertThat(handledVersions).containsExactly(3L);
    assertThat(watermark).hasValue(3L);
    assertThat(lastResult).hasValue("STALE");
    assertThat(counter(metrics, "stale")).isEqualTo(2.0);
    assertThat(counter(metrics, "processed")).isEqualTo(1.0);
    assertThat(statements)
        .anyMatch(
            sql ->
                sql.startsWith("INSERT INTO pas.consumer_policy_versions")
                    && sql.contains(
                        "WHERE pas.consumer_policy_versions.last_policy_version < EXCLUDED.last_policy_version"));
  }

  @Test
  void eventIdOnlyProcessesOlderUnseenEventsAndDeduplicatesRepeatedIds() {
    AtomicInteger consumedEventInsertCount = new AtomicInteger();
    List<String> statements = new ArrayList<>();
    JdbcClient jdbc =
        jdbc(
            statements,
            sql -> {
              JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
              when(statement.param(anyString(), any())).thenReturn(statement);
              when(statement.update())
                  .thenAnswer(
                      invocation ->
                          sql.startsWith("INSERT INTO pas.consumed_events")
                              ? consumedEventInsertCount.getAndIncrement() < 2 ? 1 : 0
                              : 1);
              JdbcClient.MappedQuerySpec<Long> query = mock(JdbcClient.MappedQuerySpec.class);
              when(statement.query(Long.class)).thenReturn(query);
              when(query.optional()).thenReturn(Optional.of(3L));
              return statement;
            });
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    IdempotentEventProcessor processor = processor(jdbc, metrics);
    List<Long> handledVersions = new ArrayList<>();
    String olderEvent = event("00000000-0000-0000-0000-000000000005", 1);

    processor.process(
        GROUP,
        TOPIC,
        event("00000000-0000-0000-0000-000000000004", 3),
        VersionPolicy.EVENT_ID_ONLY,
        envelope -> handledVersions.add(envelope.policyVersion()));
    processor.process(
        GROUP,
        TOPIC,
        olderEvent,
        VersionPolicy.EVENT_ID_ONLY,
        envelope -> handledVersions.add(envelope.policyVersion()));
    processor.process(
        GROUP,
        TOPIC,
        olderEvent,
        VersionPolicy.EVENT_ID_ONLY,
        envelope -> handledVersions.add(-1L));

    assertThat(handledVersions).containsExactly(3L, 1L);
    assertThat(counter(metrics, "processed")).isEqualTo(2.0);
    assertThat(counter(metrics, "duplicate")).isEqualTo(1.0);
    assertThat(statements).noneMatch(sql -> sql.contains("consumer_policy_versions"));
  }

  private static JdbcClient jdbc(
      List<String> statements,
      java.util.function.Function<String, JdbcClient.StatementSpec> statementFactory) {
    JdbcClient jdbc = mock(JdbcClient.class);
    when(jdbc.sql(anyString()))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              statements.add(sql);
              return statementFactory.apply(sql);
            });
    return jdbc;
  }

  private static IdempotentEventProcessor processor(JdbcClient jdbc, MeterRegistry metrics) {
    return new IdempotentEventProcessor(jdbc, new EventEnvelopes(new ObjectMapper()), metrics);
  }

  private static String event(String eventId, long version) {
    return """
        {"eventId":"%s","eventType":"DocumentRequested","schemaVersion":1,"policyNumber":"%s","policyVersion":%d,"occurredAt":"2025-01-01T00:00:00Z","correlationId":"00000000-0000-0000-0000-000000000010","data":{}}
        """
        .formatted(eventId, POLICY_NUMBER, version)
        .trim();
  }

  private static double counter(MeterRegistry metrics, String result) {
    return metrics
        .get("pas.events.consumed")
        .tag("topic", TOPIC)
        .tag("group", GROUP)
        .tag("result", result)
        .counter()
        .count();
  }
}
