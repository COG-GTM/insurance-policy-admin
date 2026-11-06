package com.acme.insurance.pas.policyservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.insurance.pas.persistence.EventOutboxJdbcRepository;
import com.acme.insurance.pas.persistence.OutboxRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxRelayTest {

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);

  final EventOutboxJdbcRepository outbox = mock(EventOutboxJdbcRepository.class);

  @SuppressWarnings("unchecked")
  final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  final List<String> sent = new ArrayList<>();

  OutboxRelay relay(int maxAttempts) {
    EventsProperties properties =
        new EventsProperties(
            true, false, 100, Duration.ofSeconds(1), Duration.ofDays(7), 3, maxAttempts, 3);
    when(kafka.send(argThat((ProducerRecord<String, String> r) -> true)))
        .thenAnswer(
            inv -> {
              ProducerRecord<String, String> r = inv.getArgument(0);
              if (r.value().contains("poison")) {
                return CompletableFuture.failedFuture(new RecordTooLargeException("too large"));
              }
              sent.add(new String(r.headers().lastHeader("eventId").value()));
              return CompletableFuture.completedFuture(mock(SendResult.class));
            });
    return new OutboxRelay(outbox, kafka, properties, CLOCK);
  }

  static OutboxRow row(long seq, String key, String payload, int attempts) {
    return new OutboxRow(
        seq,
        "e" + seq,
        "pas.policy.created",
        "pas.policy.created",
        key,
        payload,
        null,
        null,
        attempts,
        null);
  }

  @Test
  void aFailureBelowTheLimitHoldsBackOnlyThatPolicy() {
    when(outbox.pending(100))
        .thenReturn(
            List.of(
                row(1, "POL-A", "poison", 0), row(2, "POL-A", "{}", 0), row(3, "POL-B", "{}", 0)));

    relay(10).relay();

    verify(outbox).markFailed(eq(1L), anyString());
    verify(outbox, never()).park(anyLong(), org.mockito.ArgumentMatchers.any());
    verify(outbox, never()).markPublished(eq(2L), org.mockito.ArgumentMatchers.any());
    assertThat(sent).containsExactly("e3");
  }

  @Test
  void consecutiveFailuresOnSeveralPoliciesStopTheRun() {
    when(outbox.pending(100))
        .thenReturn(
            List.of(
                row(1, "POL-A", "poison", 0),
                row(2, "POL-B", "poison", 0),
                row(3, "POL-C", "poison", 0),
                row(4, "POL-D", "{}", 0)));

    relay(10).relay();

    verify(outbox).markFailed(eq(3L), anyString());
    verify(kafka, times(3)).send(argThat((ProducerRecord<String, String> r) -> true));
    assertThat(sent).isEmpty();
  }

  @Test
  void aSuccessResetsTheFailureCount() {
    when(outbox.pending(100))
        .thenReturn(
            List.of(
                row(1, "POL-A", "poison", 0),
                row(2, "POL-B", "poison", 0),
                row(3, "POL-C", "{}", 0),
                row(4, "POL-D", "poison", 0),
                row(5, "POL-E", "{}", 0)));

    relay(10).relay();

    assertThat(sent).containsExactly("e3", "e5");
  }

  @Test
  void aRowAtTheLimitIsParkedAndOnlyItsPolicyWaits() {
    when(outbox.pending(100))
        .thenReturn(
            List.of(
                row(1, "POL-A", "poison", 9), row(2, "POL-A", "{}", 0), row(3, "POL-B", "{}", 0)));

    relay(10).relay();

    verify(outbox).park(1L, CLOCK.instant().atZone(ZoneOffset.UTC).toLocalDateTime());
    verify(outbox, never()).markPublished(eq(2L), org.mockito.ArgumentMatchers.any());
    verify(outbox).markPublished(eq(3L), org.mockito.ArgumentMatchers.any());
    assertThat(sent).containsExactly("e3");
  }
}
