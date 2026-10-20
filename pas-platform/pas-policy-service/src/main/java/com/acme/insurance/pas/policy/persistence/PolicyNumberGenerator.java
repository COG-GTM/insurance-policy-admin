package com.acme.insurance.pas.policy.persistence;

import com.acme.insurance.pas.common.domain.PolicyNumbers;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * POLNEW policy-number generation: next value of {@code pas.policy_seq}, formatted as COBOL does.
 */
@Component
public class PolicyNumberGenerator {

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
   * POLNEW-format number for the next sequence value. At parity this repeats for ten consecutive
   * sequence values (see {@link PolicyNumbers}), so it is not yet a safe insert key: AB-525 must
   * settle the numbering decision in docs/modernization/open-questions.md before using it for new
   * business.
   */
  public String nextPolicyNumber() {
    return PolicyNumbers.fromSequence(nextSequenceValue());
  }
}
