package com.acme.insurance.pas.policy.integration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parks dead letters and replays them (AB-528). Replay republishes the original record value, key
 * and {@code eventId}/{@code eventType} headers to the original topic; consumers are idempotent on
 * {@code eventId}, so replaying an event that was already processed has no effect.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class DeadLetterService {

  public static final String PARKED = "PARKED";
  public static final String REPLAYED = "REPLAYED";
  public static final String REPLAY_HEADER = "pasReplayOfDeadLetter";
  private static final int MAX_ERROR_LENGTH = 1000;
  private static final int LIST_LIMIT = 200;
  private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);
  private static final String COLUMNS =
      "dead_letter_id, dlt_topic, original_topic, consumer_group, message_key, event_id,"
          + " event_type, error_class, error_message, status, recorded_at, replay_count,"
          + " replayed_at, replayed_by, payload";
  private static final String MARK_REPLAYED_BY_ID =
      "UPDATE pas.dead_letters SET status = ?, replay_count = replay_count + 1, replayed_at = LOCALTIMESTAMP, replayed_by = ? WHERE dead_letter_id = ?";

  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafka;

  public DeadLetterService(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
    this.jdbc = jdbc;
    this.kafka = kafka;
  }

  /** Records a {@code .dlt} record once (idempotent on its topic/partition/offset). */
  public void record(ConsumerRecord<String, String> r) {
    String originalTopic = header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC);
    if (originalTopic == null) {
      originalTopic =
          r.topic().endsWith(IntegrationConfiguration.DLT_SUFFIX)
              ? r.topic()
                  .substring(0, r.topic().length() - IntegrationConfiguration.DLT_SUFFIX.length())
              : r.topic();
    }
    String errorClass = header(r, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    if (errorClass == null) {
      errorClass = header(r, KafkaHeaders.DLT_EXCEPTION_FQCN);
    }
    jdbc.update(
        "INSERT INTO pas.dead_letters (dlt_topic, dlt_partition, dlt_offset, original_topic,"
            + " consumer_group, message_key, event_id, event_type, payload, error_class,"
            + " error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (dlt_topic, dlt_partition, dlt_offset) DO NOTHING",
        r.topic(),
        r.partition(),
        r.offset(),
        originalTopic,
        header(r, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP),
        r.key(),
        truncate(header(r, "eventId"), 64),
        truncate(header(r, "eventType"), 100),
        r.value(),
        truncate(errorClass, 255),
        truncate(header(r, KafkaHeaders.DLT_EXCEPTION_MESSAGE), MAX_ERROR_LENGTH));
  }

  public List<DeadLetterView> list(String status) {
    if (status == null || status.isBlank()) {
      return jdbc.query(
          "SELECT " + COLUMNS + " FROM pas.dead_letters ORDER BY dead_letter_id LIMIT ?",
          (rs, n) -> view(rs),
          LIST_LIMIT);
    }
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM pas.dead_letters WHERE status = ?"
            + " ORDER BY dead_letter_id LIMIT ?",
        (rs, n) -> view(rs),
        status.strip().toUpperCase(Locale.ROOT),
        LIST_LIMIT);
  }

  public Optional<DeadLetterView> find(long id) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM pas.dead_letters WHERE dead_letter_id = ?",
            (rs, n) -> view(rs),
            id)
        .stream()
        .findFirst();
  }

  /** Republishes the dead letter to its original topic and marks it {@code REPLAYED}. */
  @Transactional
  public Optional<DeadLetterView> replay(long id, String operator) {
    Optional<DeadLetterView> row =
        jdbc
            .query(
                "SELECT " + COLUMNS + " FROM pas.dead_letters WHERE dead_letter_id = ? FOR UPDATE",
                (rs, n) -> view(rs),
                id)
            .stream()
            .findFirst();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    DeadLetterView d = row.get();
    ProducerRecord<String, String> out =
        new ProducerRecord<>(d.originalTopic(), d.messageKey(), d.payload());
    if (d.eventType() != null) {
      out.headers().add("eventType", d.eventType().getBytes(StandardCharsets.UTF_8));
    }
    if (d.eventId() != null) {
      out.headers().add("eventId", d.eventId().getBytes(StandardCharsets.UTF_8));
    }
    out.headers().add(REPLAY_HEADER, Long.toString(id).getBytes(StandardCharsets.UTF_8));
    try {
      kafka.send(out).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DeadLetterReplayException(id, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new DeadLetterReplayException(id, e);
    }
    jdbc.update(MARK_REPLAYED_BY_ID, REPLAYED, truncate(operator, 255), id);
    return find(id);
  }

  private static DeadLetterView view(ResultSet rs) throws SQLException {
    return new DeadLetterView(
        rs.getLong("dead_letter_id"),
        rs.getString("dlt_topic"),
        rs.getString("original_topic"),
        rs.getString("consumer_group"),
        rs.getString("message_key"),
        rs.getString("event_id"),
        rs.getString("event_type"),
        rs.getString("error_class"),
        rs.getString("error_message"),
        rs.getString("status"),
        rs.getObject("recorded_at", LocalDateTime.class),
        rs.getInt("replay_count"),
        rs.getObject("replayed_at", LocalDateTime.class),
        rs.getString("replayed_by"),
        rs.getString("payload"));
  }

  private static String header(ConsumerRecord<String, String> r, String name) {
    Header h = r.headers().lastHeader(name);
    return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  private static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
