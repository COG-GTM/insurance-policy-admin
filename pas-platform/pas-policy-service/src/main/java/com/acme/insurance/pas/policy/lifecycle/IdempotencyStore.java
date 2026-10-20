package com.acme.insurance.pas.policy.lifecycle;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code pas.idempotency_keys}: which policy a caller's Idempotency-Key created. */
@Component
class IdempotencyStore {

  record Entry(String requestHash, String policyNumber) {}

  private final JdbcTemplate jdbc;

  IdempotencyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Entry> find(String subject, String operation, String key) {
    return jdbc
        .query(
            "SELECT request_hash, policy_number FROM pas.idempotency_keys"
                + " WHERE caller_subject = ? AND operation = ? AND idempotency_key = ?",
            (rs, i) -> new Entry(rs.getString(1), rs.getString(2).stripTrailing()),
            subject,
            operation,
            key)
        .stream()
        .findFirst();
  }

  /**
   * Inserts the key; a concurrent request with the same key blocks on the primary key and then
   * fails with {@code DuplicateKeyException} once this transaction commits.
   */
  void insert(String subject, String operation, String key, String requestHash, String number) {
    jdbc.update(
        "INSERT INTO pas.idempotency_keys"
            + " (caller_subject, operation, idempotency_key, request_hash, policy_number)"
            + " VALUES (?, ?, ?, ?, ?)",
        subject,
        operation,
        key,
        requestHash,
        number);
  }
}
