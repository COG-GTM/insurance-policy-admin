package com.acme.insurance.pas.policy.newbusiness;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
  private final JdbcClient jdbc;

  public IdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertIfAbsent(String subject, String key, String requestHash) {
    return jdbc.sql(
                """
            INSERT INTO pas.idempotency_keys (caller_sub, idempotency_key, request_hash)
            VALUES (:subject, :key, :requestHash)
            ON CONFLICT (caller_sub, idempotency_key) DO NOTHING
            """)
            .param("subject", subject)
            .param("key", key)
            .param("requestHash", requestHash)
            .update()
        == 1;
  }

  public Optional<StoredResponse> find(String subject, String key) {
    return jdbc.sql(
            """
            SELECT request_hash, response_status, response_location, response_json::text AS body
            FROM pas.idempotency_keys
            WHERE caller_sub = :subject AND idempotency_key = :key
            """)
        .param("subject", subject)
        .param("key", key)
        .query(
            (row, rowNumber) ->
                new StoredResponse(
                    row.getString("request_hash"),
                    (Integer) row.getObject("response_status"),
                    row.getString("response_location"),
                    row.getString("body")))
        .optional();
  }

  public void saveResponse(
      String subject, String key, int status, String location, String responseJson) {
    jdbc.sql(
            """
            UPDATE pas.idempotency_keys
            SET response_status = :status, response_location = :location,
                response_json = CAST(:responseJson AS jsonb)
            WHERE caller_sub = :subject AND idempotency_key = :key
            """)
        .param("status", status)
        .param("location", location)
        .param("responseJson", responseJson)
        .param("subject", subject)
        .param("key", key)
        .update();
  }

  public record StoredResponse(String requestHash, Integer status, String location, String body) {}
}
