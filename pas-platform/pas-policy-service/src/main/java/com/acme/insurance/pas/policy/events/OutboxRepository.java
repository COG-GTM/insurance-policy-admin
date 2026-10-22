package com.acme.insurance.pas.policy.events;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<OutboxRecord> unpublished(int batchSize) {
    return jdbc.sql(
            """
            SELECT id, aggregate_id, topic, payload::text AS payload
            FROM pas.outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            """)
        .param("batchSize", batchSize)
        .query(
            (row, rowNumber) ->
                new OutboxRecord(
                    row.getLong("id"),
                    row.getString("aggregate_id").trim(),
                    row.getString("topic"),
                    row.getString("payload")))
        .list();
  }

  public void markPublished(long id) {
    jdbc.sql("UPDATE pas.outbox SET published_at = now() WHERE id = :id").param("id", id).update();
  }

  public long countUnpublished() {
    return jdbc.sql("SELECT count(*) FROM pas.outbox WHERE published_at IS NULL")
        .query(Long.class)
        .single();
  }

  public record OutboxRecord(long id, String aggregateId, String topic, String payload) {}
}
