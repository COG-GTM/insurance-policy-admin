package com.acme.insurance.pas.underwriting;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The configurable part of UNDWRT: decision band thresholds (hardcoded 300 / 600 / 800 in {@code
 * 5000-RENDER-DECISION}), the per-branch accumulation limit (hardcoded $500M in {@code
 * 4000-CHECK-ACCUMULATION}, quirk Q-16) and the claims look-back window ({@code CURRENT DATE - 5
 * YEARS}). {@link #legacy()} is the COBOL behaviour.
 *
 * @param referSeniorFrom first score referred to a senior underwriter (RS)
 * @param referManagerFrom first score referred to the underwriting manager (RM)
 * @param declineFrom first score declined automatically (DC)
 * @param branchAccumulationLimit active coverage limit per branch above which +300 applies
 * @param lossHistoryYears years of claims history read for count and loss ratio
 */
public record UnderwritingRules(
    int referSeniorFrom,
    int referManagerFrom,
    int declineFrom,
    BigDecimal branchAccumulationLimit,
    int lossHistoryYears) {

  public UnderwritingRules {
    Objects.requireNonNull(branchAccumulationLimit, "branchAccumulationLimit");
    if (referSeniorFrom <= 0
        || referManagerFrom <= referSeniorFrom
        || declineFrom <= referManagerFrom) {
      throw new IllegalArgumentException(
          "thresholds must satisfy 0 < referSeniorFrom < referManagerFrom < declineFrom");
    }
    if (branchAccumulationLimit.signum() <= 0) {
      throw new IllegalArgumentException("branchAccumulationLimit must be positive");
    }
    if (lossHistoryYears <= 0) {
      throw new IllegalArgumentException("lossHistoryYears must be positive");
    }
  }

  /** The values UNDWRT hardcodes today. */
  public static UnderwritingRules legacy() {
    return new UnderwritingRules(300, 600, 800, new BigDecimal("500000000.00"), 5);
  }

  DecisionBand band(int riskScore) {
    if (riskScore < referSeniorFrom) {
      return DecisionBand.ACCEPT;
    }
    if (riskScore < referManagerFrom) {
      return DecisionBand.REFER_SENIOR;
    }
    if (riskScore < declineFrom) {
      return DecisionBand.REFER_MANAGER;
    }
    return DecisionBand.DECLINE;
  }
}
