package com.acme.insurance.pas.policy.events;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<OutboxRecord> unpublished(int batchSize) {
    return unpublished(batchSize, Set.of());
  }

  public List<OutboxRecord> unpublished(int batchSize, Set<String> excludedPolicies) {
    String query =
        """
        SELECT id, aggregate_id, topic, payload::text AS payload
        FROM (
          SELECT DISTINCT ON (aggregate_id) id, aggregate_id, topic, payload
          FROM pas.outbox
          WHERE published_at IS NULL
        """
            + (excludedPolicies.isEmpty() ? "" : "  AND aggregate_id NOT IN (:excludedPolicies)\n")
            + """
          ORDER BY aggregate_id, id
        ) policy_heads
        ORDER BY id
        LIMIT :batchSize
        """;
    var statement = jdbc.sql(query).param("batchSize", batchSize);
    if (!excludedPolicies.isEmpty()) {
      statement = statement.param("excludedPolicies", excludedPolicies);
    }
    return statement
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
