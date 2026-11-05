package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PASAPP.EVENT_OUTBOX (V50, ADR 0001 D4). {@link #append} runs inside the business transaction; the
 * relay reads {@link #pending}, publishes in {@code outbox_seq} order and marks each row. SQL runs
 * unchanged on DB2 and PostgreSQL.
 */
public class EventOutboxJdbcRepository {

  static final int ERROR_LENGTH = 512;

  private final JdbcClient jdbc;

  public EventOutboxJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void append(OutboxRow row) {
    new Insert("pasapp.event_outbox")
        .set("event_id", row.eventId())
        .set("event_type", row.eventType())
        .set("topic", row.topic())
        .set("message_key", row.messageKey())
        .set("payload", row.payload())
        .execute(jdbc);
  }

  /** Up to {@code limit} unpublished rows, oldest first. */
  public List<OutboxRow> pending(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    // FETCH FIRST takes a literal on DB2 for z/OS; limit is an int, so formatting it is safe.
    return jdbc.sql(
            "SELECT * FROM pasapp.event_outbox WHERE published_at IS NULL"
                + " ORDER BY outbox_seq FETCH FIRST "
                + limit
                + " ROWS ONLY")
        .query((rs, n) -> map(rs))
        .list();
  }

  /** Every row of one event key (policy number), oldest first. */
  public List<OutboxRow> findByMessageKey(String messageKey) {
    return jdbc.sql("SELECT * FROM pasapp.event_outbox WHERE message_key = ? ORDER BY outbox_seq")
        .param(messageKey)
        .query((rs, n) -> map(rs))
        .list();
  }

  public void markPublished(long outboxSeq, LocalDateTime publishedAt) {
    jdbc.sql(
            "UPDATE pasapp.event_outbox SET published_at = ?, attempts = attempts + 1,"
                + " last_error = NULL WHERE outbox_seq = ?")
        .params(publishedAt, outboxSeq)
        .update();
  }

  public void markFailed(long outboxSeq, String error) {
    String message = error == null ? "unknown" : error;
    if (message.length() > ERROR_LENGTH) {
      message = message.substring(0, ERROR_LENGTH);
    }
    jdbc.sql(
            "UPDATE pasapp.event_outbox SET attempts = attempts + 1, last_error = ?"
                + " WHERE outbox_seq = ?")
        .params(message, outboxSeq)
        .update();
  }

  /** Deletes rows published before {@code cutoff}; returns how many. */
  public int deletePublishedBefore(LocalDateTime cutoff) {
    return jdbc.sql(
            "DELETE FROM pasapp.event_outbox WHERE published_at IS NOT NULL AND published_at < ?")
        .param(cutoff)
        .update();
  }

  private static OutboxRow map(ResultSet rs) throws SQLException {
    return new OutboxRow(
        rs.getLong("outbox_seq"),
        text(rs, "event_id"),
        text(rs, "event_type"),
        text(rs, "topic"),
        text(rs, "message_key"),
        rs.getString("payload"),
        timestamp(rs, "created_at"),
        timestamp(rs, "published_at"),
        rs.getInt("attempts"),
        rs.getString("last_error"));
  }
}
