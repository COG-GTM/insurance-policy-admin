package com.acme.insurance.pas.policyservice.events;

import com.acme.insurance.pas.persistence.EventOutboxJdbcRepository;
import com.acme.insurance.pas.persistence.OutboxRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Publishes outbox rows to Kafka in {@code outbox_seq} order, key = policy number, headers {@code
 * eventId}, {@code eventType}, {@code schemaVersion}. A row is marked published only after the
 * broker acknowledged it; on failure the run stops so later events of the same policy cannot
 * overtake it, and the row is retried on the next run (at-least-once, consumers deduplicate on
 * {@code eventId}). Published rows are deleted after the retention period. Running more than one
 * relay can publish a row twice, which the idempotency key absorbs; a single active relay is
 * recommended (FU-525-7).
 *
 * <p>AB-528: a row that still fails after {@code pas.events.max-attempts} tries is parked (V80)
 * instead of blocking the whole outbox; it and later rows of the same policy wait for a replay
 * through {@code /api/v1/ops/events}, while other policies keep flowing.
 */
public class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final EventOutboxJdbcRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final EventsProperties properties;
  private final Clock clock;

  public OutboxRelay(
      EventOutboxJdbcRepository outbox,
      KafkaTemplate<String, String> kafka,
      EventsProperties properties,
      Clock clock) {
    this.outbox = outbox;
    this.kafka = kafka;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${pas.events.relay-interval:PT1S}")
  public void relay() {
    int published = 0;
    Set<String> parkedKeys = new HashSet<>();
    for (OutboxRow row : outbox.pending(properties.batchSize())) {
      if (parkedKeys.contains(row.messageKey())) {
        continue;
      }
      try {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(row.topic(), row.messageKey(), row.payload());
        record.headers().add("eventId", row.eventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("schemaVersion", "1".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        outbox.markPublished(row.outboxSeq(), LocalDateTime.now(clock));
        published++;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        outbox.markFailed(row.outboxSeq(), "interrupted");
        return;
      } catch (Exception e) {
        LOG.warn(
            "outbox relay failed: eventId={} eventType={} error={}",
            row.eventId(),
            row.eventType(),
            e.getClass().getSimpleName());
        outbox.markFailed(row.outboxSeq(), e.getClass().getSimpleName() + ": " + e.getMessage());
        if (row.attempts() + 1 < properties.maxAttempts()) {
          break;
        }
        outbox.park(row.outboxSeq(), LocalDateTime.now(clock));
        parkedKeys.add(row.messageKey());
        LOG.error(
            "outbox relay parked event after {} attempts: eventId={} eventType={}",
            row.attempts() + 1,
            row.eventId(),
            row.eventType());
      }
    }
    if (published > 0) {
      LOG.debug("outbox relay published {} events", published);
    }
  }

  @Scheduled(fixedDelayString = "${pas.events.purge-interval:PT1H}")
  public void purge() {
    int deleted =
        outbox.deletePublishedBefore(LocalDateTime.now(clock).minus(properties.retention()));
    if (deleted > 0) {
      LOG.info("outbox purge deleted {} published events", deleted);
    }
  }
}
