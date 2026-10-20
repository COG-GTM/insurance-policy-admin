package com.acme.insurance.pas.policy.persistence;

import com.acme.insurance.pas.common.domain.PolicyNumbers;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * POLNEW policy-number generation from {@code pas.policy_seq}. New business uses the quirk register
 * Q-27 fix ({@link PolicyNumbers#forNewBusiness}): {@code POL} + 9 digits, one number per sequence
 * value, skipping any number that already exists (e.g. loaded by the DB2 data migration).
 */
@Component
public class PolicyNumberGenerator {

  /** Guards only against concurrent writers; each collision first skips past every used number. */
  static final int MAX_ATTEMPTS = 10;

  static final String SKIP_PAST_USED =
      "SELECT setval('pas.policy_seq', GREATEST((SELECT last_value FROM pas.policy_seq),"
          + " (SELECT COALESCE(MAX(CAST(SUBSTRING(policy_number FROM 4 FOR 9) AS BIGINT)), 0)"
          + " FROM pas.policies WHERE policy_number ~ '^POL[0-9]{9}$')))";

  private final JdbcOperations jdbc;

  public PolicyNumberGenerator(JdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  public long nextSequenceValue() {
    Long value = jdbc.queryForObject("SELECT nextval('pas.policy_seq')", Long.class);
    if (value == null) {
      throw new IllegalStateException("pas.policy_seq returned no value");
    }
    return value;
  }

  /**
   * Next unused new-business policy number. Distinct sequence values always give distinct numbers,
   * so concurrent callers never collide with each other; the existence check guards against rows
   * that were not numbered by this generator (e.g. migrated). On a collision the sequence jumps
   * past the highest {@code POL} + 9-digit number in use, so an occupied range of any length costs
   * one extra query rather than exhausting the attempts. Running out of 9-digit numbers fails in
   * {@link PolicyNumbers#forNewBusiness}. {@code pk_policies} remains the final guard.
   */
  public String nextPolicyNumber() {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      String number = PolicyNumbers.forNewBusiness(nextSequenceValue());
      Boolean taken =
          jdbc.queryForObject(
              "SELECT EXISTS (SELECT 1 FROM pas.policies WHERE policy_number = ?)",
              Boolean.class,
              number);
      if (!Boolean.TRUE.equals(taken)) {
        return number;
      }
      jdbc.queryForObject(SKIP_PAST_USED, Long.class);
    }
    throw new IllegalStateException(
        "no free policy number after " + MAX_ATTEMPTS + " pas.policy_seq values");
  }
}
