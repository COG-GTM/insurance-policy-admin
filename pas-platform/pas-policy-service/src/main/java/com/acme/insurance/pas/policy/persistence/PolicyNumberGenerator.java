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

  public String nextPolicyNumber() {
    return PolicyNumbers.fromSequence(nextSequenceValue());
  }
}
