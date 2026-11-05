package com.acme.insurance.pas.policyservice.newbusiness;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pas.new-business.*}.
 *
 * @param policyNumberFormat {@link String#format} pattern applied to the POLICY_SEQ value; the
 *     result must fit POLICY_NUMBER CHAR(12). Default {@code POL-%08d}, the format of the existing
 *     policy numbers; the production rule is confirmed by the mainframe team (Q-08, FU-05)
 * @param termMonths policy term in months for the expiry date (Q-09: no rule in the COBOL)
 */
@ConfigurationProperties("pas.new-business")
public record NewBusinessProperties(
    @DefaultValue("POL-%08d") String policyNumberFormat, @DefaultValue("12") int termMonths) {

  static final int POLICY_NUMBER_LENGTH = 12;

  public NewBusinessProperties {
    if (termMonths <= 0) {
      throw new IllegalArgumentException("pas.new-business.term-months must be positive");
    }
    String sample = String.format(policyNumberFormat, 1);
    if (sample.isBlank() || sample.length() > POLICY_NUMBER_LENGTH) {
      throw new IllegalArgumentException(
          "pas.new-business.policy-number-format must give 1-12 chars");
    }
  }

  /** The policy number for a sequence value; never truncated (the Q-08 collision). */
  public String policyNumber(long sequence) {
    String number = String.format(policyNumberFormat, sequence);
    if (number.length() > POLICY_NUMBER_LENGTH) {
      throw new IllegalStateException(
          "POLICY_SEQ value " + sequence + " no longer fits the policy number format");
    }
    return number;
  }
}
