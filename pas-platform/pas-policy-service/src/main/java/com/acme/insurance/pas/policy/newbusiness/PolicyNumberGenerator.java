package com.acme.insurance.pas.policy.newbusiness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class PolicyNumberGenerator {
  private static final Logger LOGGER = LoggerFactory.getLogger(PolicyNumberGenerator.class);

  private final JdbcClient jdbc;

  public PolicyNumberGenerator(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public String next() {
    long sequence = jdbc.sql("SELECT nextval('pas.policy_seq')").query(Long.class).single();
    try {
      return PolicyNumbers.modern(sequence);
    } catch (IllegalArgumentException exception) {
      LOGGER.error("pas.policy.number.exhausted");
      throw new PolicyNumberExhaustedException();
    }
  }
}
