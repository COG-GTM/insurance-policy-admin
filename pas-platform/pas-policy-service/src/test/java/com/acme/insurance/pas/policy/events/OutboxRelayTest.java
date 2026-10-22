package com.acme.insurance.pas.policy.events;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRelayTest {
  @Test
  void failingPolicyWithMoreThanBatchSizeRowsDoesNotBlockAnotherPolicy() {
    JdbcClient jdbc = mock(JdbcClient.class);
    JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
    JdbcClient.MappedQuerySpec<Boolean> query = mock(JdbcClient.MappedQuerySpec.class);
    when(jdbc.sql(anyString())).thenReturn(statement);
    when(statement.param(anyString(), eq(525_525L))).thenReturn(statement);
    when(statement.query(Boolean.class)).thenReturn(query);
    when(query.single()).thenReturn(true);

    List<OutboxRepository.OutboxRecord> pendingRows =
        new ArrayList<>(
            List.of(
                new OutboxRepository.OutboxRecord(1, "POL000000001", "topic", "failed-head"),
                new OutboxRepository.OutboxRecord(2, "POL000000001", "topic", "failed-next"),
                new OutboxRepository.OutboxRecord(3, "POL000000001", "topic", "failed-later"),
                new OutboxRepository.OutboxRecord(4, "POL000000002", "topic", "other-policy")));
    OutboxRepository outbox = mock(OutboxRepository.class);
    when(outbox.unpublished(anyInt(), anySet()))
        .thenAnswer(
            invocation -> {
              int batchSize = invocation.getArgument(0);
              Set<String> excludedPolicies = invocation.getArgument(1);
              Set<String> selectedPolicies = new HashSet<>();
              return pendingRows.stream()
                  .sorted(Comparator.comparingLong(OutboxRepository.OutboxRecord::id))
                  .filter(record -> !excludedPolicies.contains(record.aggregateId()))
                  .filter(record -> selectedPolicies.add(record.aggregateId()))
                  .limit(batchSize)
                  .toList();
            });
    doAnswer(
            invocation -> {
              long publishedId = invocation.getArgument(0);
              pendingRows.removeIf(record -> record.id() == publishedId);
              return null;
            })
        .when(outbox)
        .markPublished(anyLong());

    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send("topic", "POL000000001", "failed-head"))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
    when(kafka.send("topic", "POL000000002", "other-policy"))
        .thenReturn(CompletableFuture.completedFuture(null));
    MeterRegistry metrics = mock(MeterRegistry.class);
    when(metrics.counter(anyString(), anyString(), anyString())).thenReturn(mock(Counter.class));
    OutboxRelay relay =
        new OutboxRelay(
            jdbc,
            outbox,
            kafka,
            new OutboxRelayProperties(true, Duration.ofSeconds(1), 2, Duration.ofSeconds(1)),
            metrics);

    relay.relay();

    verify(kafka).send("topic", "POL000000001", "failed-head");
    verify(kafka, never()).send("topic", "POL000000001", "failed-next");
    verify(kafka, never()).send("topic", "POL000000001", "failed-later");
    verify(kafka).send("topic", "POL000000002", "other-policy");
    verify(outbox, never()).markPublished(1);
    verify(outbox, never()).markPublished(2);
    verify(outbox, never()).markPublished(3);
    verify(outbox).markPublished(4);
    verify(outbox).unpublished(2, Set.of());
    verify(outbox).unpublished(1, Set.of("POL000000001"));
  }
}
