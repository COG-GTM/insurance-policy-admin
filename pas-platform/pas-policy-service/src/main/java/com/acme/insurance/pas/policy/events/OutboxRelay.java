package com.acme.insurance.pas.policy.events;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
    name = "pas.outbox.relay.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxRelay {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
  private static final long ADVISORY_LOCK_KEY = 525_525L;

  private final JdbcClient jdbc;
  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final OutboxRelayProperties properties;
  private final MeterRegistry metrics;
  private final Map<String, FailureBackoff> failureBackoff = new ConcurrentHashMap<>();

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Collaborators are Spring-managed service dependencies.")
  public OutboxRelay(
      JdbcClient jdbc,
      OutboxRepository outbox,
      KafkaTemplate<String, String> kafka,
      OutboxRelayProperties properties,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.kafka = kafka;
    this.properties = properties;
    this.metrics = metrics;
  }

  @PostConstruct
  void registerBacklogGauge() {
    metrics.gauge("pas.outbox.unpublished", outbox, OutboxRepository::countUnpublished);
  }

  @Scheduled(fixedDelayString = "${pas.outbox.relay.poll-interval:PT1S}")
  @Transactional
  public void relay() {
    Boolean locked =
        jdbc.sql("SELECT pg_try_advisory_xact_lock(:lockKey)")
            .param("lockKey", ADVISORY_LOCK_KEY)
            .query(Boolean.class)
            .single();
    if (!Boolean.TRUE.equals(locked)) {
      return;
    }
    int published = 0;
    while (published < properties.batchSize() && !Thread.currentThread().isInterrupted()) {
      Set<String> excludedPolicies = policiesInBackoff();
      var records = outbox.unpublished(properties.batchSize() - published, excludedPolicies);
      if (records.isEmpty()) {
        break;
      }
      int publishedThisPass = 0;
      for (OutboxRepository.OutboxRecord record : records) {
        try {
          kafka
              .send(record.topic(), record.aggregateId(), record.payload())
              .get(properties.ackTimeout().toMillis(), TimeUnit.MILLISECONDS);
          outbox.markPublished(record.id());
          failureBackoff.remove(record.aggregateId());
          metrics.counter("pas.outbox.published", "topic", record.topic()).increment();
          published++;
          publishedThisPass++;
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          recordFailure(record);
          break;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
          recordFailure(record);
        }
      }
      if (publishedThisPass == 0) {
        break;
      }
    }
  }

  private Set<String> policiesInBackoff() {
    long now = System.nanoTime();
    Set<String> excluded = new HashSet<>();
    failureBackoff.forEach(
        (policyNumber, backoff) -> {
          if (now - backoff.retryAtNanos() < 0) {
            excluded.add(policyNumber);
          }
        });
    return Set.copyOf(excluded);
  }

  private void recordFailure(OutboxRepository.OutboxRecord record) {
    FailureBackoff previous = failureBackoff.get(record.aggregateId());
    int failures = previous == null ? 1 : Math.min(previous.failures() + 1, 7);
    long delayMillis = Math.min(60_000L, 1_000L << (failures - 1));
    failureBackoff.put(
        record.aggregateId(),
        new FailureBackoff(
            failures, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis)));
    metrics.counter("pas.outbox.publish.failures", "topic", record.topic()).increment();
    LOGGER.error(
        "pas.outbox.publish.failed policyNumber={} topic={}", record.aggregateId(), record.topic());
  }

  private record FailureBackoff(int failures, long retryAtNanos) {}
}
