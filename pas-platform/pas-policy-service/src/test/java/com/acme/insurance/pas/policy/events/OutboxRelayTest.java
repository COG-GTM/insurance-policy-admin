package com.acme.insurance.pas.policy.events;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRelayTest {
  @Test
  void preservesPerPolicyOrderAndStopsAfterTheFirstFailure() {
    JdbcClient jdbc = mock(JdbcClient.class);
    JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
    JdbcClient.MappedQuerySpec<Boolean> query = mock(JdbcClient.MappedQuerySpec.class);
    when(jdbc.sql(anyString())).thenReturn(statement);
    when(statement.param(anyString(), eq(525_525L))).thenReturn(statement);
    when(statement.query(Boolean.class)).thenReturn(query);
    when(query.single()).thenReturn(true);

    OutboxRepository outbox = mock(OutboxRepository.class);
    when(outbox.unpublished(10))
        .thenReturn(
            List.of(
                new OutboxRepository.OutboxRecord(1, "POL000000001", "topic", "first"),
                new OutboxRepository.OutboxRecord(2, "POL000000001", "topic", "second"),
                new OutboxRepository.OutboxRecord(3, "POL000000002", "topic", "third")));
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send("topic", "POL000000001", "first"))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    when(kafka.send("topic", "POL000000002", "third"))
        .thenReturn(CompletableFuture.completedFuture(null));
    MeterRegistry metrics = mock(MeterRegistry.class);
    when(metrics.counter(anyString(), anyString(), anyString())).thenReturn(mock(Counter.class));
    OutboxRelay relay =
        new OutboxRelay(
            jdbc,
            outbox,
            kafka,
            new OutboxRelayProperties(true, Duration.ofSeconds(1), 10, Duration.ofSeconds(1)),
            metrics);

    relay.relay();

    verify(kafka).send("topic", "POL000000001", "first");
    verify(kafka, never()).send("topic", "POL000000001", "second");
    verify(kafka).send("topic", "POL000000002", "third");
    verify(outbox, never()).markPublished(1);
    verify(outbox, never()).markPublished(2);
    verify(outbox).markPublished(3);
  }
}
