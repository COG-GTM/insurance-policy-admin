package com.acme.insurance.pas.underwriting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Port of UNDWRT {@code 3000-CALCULATE-RISK-SCORE} and {@code 4000-CHECK-ACCUMULATION}, step by
 * step. Integer division truncates as in COBOL (quirk Q-06). Two proposed fixes (pending business
 * sign-off) differ from the COBOL: a step that would go below zero stores 0 instead of the absolute
 * value the unsigned {@code PIC 9(04)} keeps (Q-14; this can move the band, KD-11), and the loss
 * ratio keeps all its integer digits instead of overflowing {@code PIC S9(03)V99} at 1000% (Q-15).
 */
public final class RiskScorer {

  static final int NO_CREDIT_SCORE_BASE = 400;
  static final BigDecimal LOSS_RATIO_SURCHARGE_ABOVE = new BigDecimal("80");
  static final BigDecimal HIGH_LIMIT = new BigDecimal("5000000");
  static final BigDecimal VERY_HIGH_LIMIT = new BigDecimal("10000000");
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final UnderwritingRules rules;

  public RiskScorer(UnderwritingRules rules) {
    this.rules = Objects.requireNonNull(rules, "rules");
  }

  public RiskAssessment assess(
      UnderwritingSubject subject, ClaimsSummary claims, BigDecimal branchActiveLimit) {
    int score;
    // Base score from credit.
    if (subject.creditScore() > 0) {
      score = store((900 - subject.creditScore()) / 2);
    } else {
      score = NO_CREDIT_SCORE_BASE;
    }
    // Risk tier adjustment.
    if ("U".equals(trim(subject.riskTierCode()))) {
      score = store(score + 200);
    }
    if ("P".equals(trim(subject.riskTierCode()))) {
      score = store(score - 100);
    }
    // Loss history.
    if (claims.claimCount() > 3) {
      score = store(score + 150);
    }
    if (claims.claimCount() > 5) {
      score = store(score + 200);
    }
    // Loss ratio.
    BigDecimal lossRatio = BigDecimal.ZERO.setScale(2);
    if (subject.totalPremium().signum() > 0) {
      lossRatio =
          claims
              .totalIncurred()
              .multiply(HUNDRED)
              .divide(subject.totalPremium(), 2, RoundingMode.DOWN);
      if (lossRatio.compareTo(LOSS_RATIO_SURCHARGE_ABOVE) > 0) {
        score = store(score + 200);
      }
    }
    // High limit surcharge.
    if (subject.coverageLimit().compareTo(HIGH_LIMIT) > 0) {
      score = store(score + 100);
    }
    if (subject.coverageLimit().compareTo(VERY_HIGH_LIMIT) > 0) {
      score = store(score + 200);
    }
    // Branch accumulation.
    boolean accumulationExceeded =
        branchActiveLimit != null
            && branchActiveLimit.compareTo(rules.branchAccumulationLimit()) > 0;
    if (accumulationExceeded) {
      score = store(score + 300);
    }
    return new RiskAssessment(score, claims.claimCount(), lossRatio, accumulationExceeded);
  }

  /** Storing into the unsigned score field, with the Q-14 fix: below zero becomes 0. */
  static int store(int value) {
    return Math.max(0, value);
  }

  private static String trim(String code) {
    return code == null ? null : code.stripTrailing();
  }
}
