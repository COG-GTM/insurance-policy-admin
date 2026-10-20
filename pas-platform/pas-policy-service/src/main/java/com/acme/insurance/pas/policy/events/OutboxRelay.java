package com.acme.insurance.pas.policy.events;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes {@code pas.outbox} rows to Kafka in {@code outbox_id} order, at least once. A
 * transaction-scoped advisory lock makes one replica the active relay at a time, which keeps
 * per-policy order across replicas. On the first failed send the batch stops (later rows wait, so
 * order holds), the row's {@code attempts}/{@code last_error} are recorded and the next poll
 * retries.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class OutboxRelay {

  /** Advisory lock key ("PASO"); only the outbox relay uses it. */
  static final long LOCK_KEY = 0x5041534FL;

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int MAX_ERROR_LENGTH = 1000;

  private record Row(
      long outboxId, String eventId, String topic, String key, String eventType, String payload) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final KafkaTemplate<String, String> kafka;
  private final OutboxRelayProperties properties;

  public OutboxRelay(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      KafkaTemplate<String, String> kafka,
      OutboxRelayProperties properties) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.kafka = kafka;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${pas.outbox.relay.poll-interval:PT1S}")
  public void poll() {
    try {
      int published;
      do {
        published = relayOnce();
      } while (published == properties.batchSize());
    } catch (RuntimeException e) {
      log.warn("outbox relay poll failed: {}", e.toString());
    }
  }

  /**
   * Publishes up to one batch. Returns the number of rows published, or 0 when another replica
   * holds the relay lock or a send failed.
   */
  public int relayOnce() {
    Integer published = tx.execute(status -> publishBatch());
    return published == null ? 0 : published;
  }

  private int publishBatch() {
    Boolean locked =
        jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
    if (!Boolean.TRUE.equals(locked)) {
      return 0;
    }
    List<Row> rows =
        jdbc.query(
            "SELECT outbox_id, event_id, topic, message_key, event_type, payload FROM pas.outbox"
                + " WHERE published_at IS NULL ORDER BY outbox_id LIMIT ?",
            (rs, i) ->
                new Row(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6)),
            properties.batchSize());
    List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(rows.size());
    for (Row row : rows) {
      try {
        sends.add(kafka.send(record(row)));
      } catch (RuntimeException e) {
        sends.add(CompletableFuture.failedFuture(e));
      }
    }
    int published = 0;
    for (int i = 0; i < rows.size(); i++) {
      Row row = rows.get(i);
      try {
        sends.get(i).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        recordFailure(row, e);
        return 0;
      } catch (ExecutionException | TimeoutException | RuntimeException e) {
        recordFailure(
            row, e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e);
        return 0;
      }
      jdbc.update(
          "UPDATE pas.outbox SET published_at = LOCALTIMESTAMP, attempts = attempts + 1"
              + " WHERE outbox_id = ?",
          row.outboxId());
      published++;
    }
    return published;
  }

  private static ProducerRecord<String, String> record(Row row) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(row.topic(), row.key(), row.payload());
    record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventId", row.eventId().getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private void recordFailure(Row row, Throwable error) {
    String message = String.valueOf(error);
    if (message.length() > MAX_ERROR_LENGTH) {
      message = message.substring(0, MAX_ERROR_LENGTH);
    }
    log.warn(
        "outbox relay: publishing {} {} to {} failed; will retry: {}",
        row.eventType(),
        row.eventId(),
        row.topic(),
        message);
    jdbc.update(
        "UPDATE pas.outbox SET attempts = attempts + 1, last_error = ? WHERE outbox_id = ?",
        message,
        row.outboxId());
  }
}
