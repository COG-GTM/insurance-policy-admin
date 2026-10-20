package com.acme.insurance.pas.policy.underwriting;

import static com.acme.insurance.pas.common.copybook.CobolArithmetic.storeSigned;
import static com.acme.insurance.pas.common.copybook.CobolArithmetic.storeUnsigned;

import com.acme.insurance.pas.common.copybook.CobolArithmetic;
import com.acme.insurance.pas.common.domain.CopybookCode;
import com.acme.insurance.pas.common.domain.RiskTier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Port of UNDWRT {@code 3000-CALCULATE-RISK-SCORE}, {@code 4000-CHECK-ACCUMULATION} and {@code
 * 5000-RENDER-DECISION} at parity, including the arithmetic quirks pinned by the {@code undwrt}
 * goldens: every store into the unsigned {@code WS-RISK-SCORE PIC 9(4)} drops the sign (preferred
 * 25 - 100 = 75), the loss ratio is truncated into {@code S9(3)V99} (200000% becomes 0.00) and the
 * policy record keeps {@code score mod 1000}. Thresholds are the hardcoded UNDWRT values (quirk
 * register Q-21, preserved; configurability is OQ-11).
 */
public final class UnderwritingRiskScorer {

  static final int SCORE_DIGITS = 4;
  static final int POLICY_SCORE_DIGITS = 3;
  static final int CREDIT_DIGITS = 3;
  static final int CLAIM_COUNT_DIGITS = 5;

  static final int CREDIT_BASE = 900;
  static final int CREDIT_DIVISOR = 2;
  static final int NO_CREDIT_SCORE = 400;
  static final int SUBSTANDARD_LOAD = 200;
  static final int PREFERRED_CREDIT = 100;

  static final int CLAIMS_FIRST_BAND = 3;
  static final int CLAIMS_FIRST_LOAD = 150;
  static final int CLAIMS_SECOND_BAND = 5;
  static final int CLAIMS_SECOND_LOAD = 200;

  static final long LOSS_RATIO_BAND = 80;
  static final int LOSS_RATIO_LOAD = 200;
  static final int LOSS_RATIO_DIVISION_SCALE = 18;

  static final long HIGH_LIMIT_BAND = 5_000_000;
  static final int HIGH_LIMIT_LOAD = 100;
  static final long VERY_HIGH_LIMIT_BAND = 10_000_000;
  static final int VERY_HIGH_LIMIT_LOAD = 200;

  static final long ACCUMULATION_BAND = 500_000_000;
  static final int ACCUMULATION_LOAD = 300;

  static final int ACCEPT_BELOW = 300;
  static final int REFER_SENIOR_BELOW = 600;
  static final int REFER_MANAGER_BELOW = 800;

  private UnderwritingRiskScorer() {}

  public static RiskAssessment assess(RiskInputs in) {
    long credit = storeUnsigned(in.creditScore(), CREDIT_DIGITS);
    long score;
    if (credit > 0) {
      // COMPUTE truncates toward zero; the unsigned receiver then drops the sign (999 -> 49).
      score = storeUnsigned((CREDIT_BASE - credit) / CREDIT_DIVISOR, SCORE_DIGITS);
    } else {
      score = NO_CREDIT_SCORE;
    }

    Optional<RiskTier> tier = CopybookCode.lookup(RiskTier.class, in.riskTier());
    if (tier.filter(RiskTier.SUBSTANDARD::equals).isPresent()) {
      score = storeUnsigned(score + SUBSTANDARD_LOAD, SCORE_DIGITS);
    }
    if (tier.filter(RiskTier.PREFERRED::equals).isPresent()) {
      score = storeUnsigned(score - PREFERRED_CREDIT, SCORE_DIGITS);
    }

    long claims = storeUnsigned(in.claimCount5y(), CLAIM_COUNT_DIGITS);
    if (claims > CLAIMS_FIRST_BAND) {
      score = storeUnsigned(score + CLAIMS_FIRST_LOAD, SCORE_DIGITS);
    }
    if (claims > CLAIMS_SECOND_BAND) {
      score = storeUnsigned(score + CLAIMS_SECOND_LOAD, SCORE_DIGITS);
    }

    BigDecimal premium = storeSigned(in.totalPremium(), 9, 2);
    BigDecimal lossRatio = BigDecimal.ZERO.setScale(2);
    if (premium.signum() > 0) {
      BigDecimal incurred = storeSigned(in.incurred5y(), 11, 2);
      lossRatio =
          storeSigned(
              incurred
                  .divide(premium, LOSS_RATIO_DIVISION_SCALE, RoundingMode.DOWN)
                  .movePointRight(2),
              3,
              2);
      if (exceeds(lossRatio, LOSS_RATIO_BAND)) {
        score = storeUnsigned(score + LOSS_RATIO_LOAD, SCORE_DIGITS);
      }
    }

    BigDecimal limit = storeSigned(in.policyLimit(), 11, 2);
    if (exceeds(limit, HIGH_LIMIT_BAND)) {
      score = storeUnsigned(score + HIGH_LIMIT_LOAD, SCORE_DIGITS);
    }
    if (exceeds(limit, VERY_HIGH_LIMIT_BAND)) {
      score = storeUnsigned(score + VERY_HIGH_LIMIT_LOAD, SCORE_DIGITS);
    }

    BigDecimal accumulation = storeSigned(in.branchAccumulation(), 13, 2);
    boolean accumulationExceeded = exceeds(accumulation, ACCUMULATION_BAND);
    if (accumulationExceeded) {
      score = storeUnsigned(score + ACCUMULATION_LOAD, SCORE_DIGITS);
    }

    UnderwritingDecisionCode decision = decide(score);
    return new RiskAssessment(
        (int) score,
        decision,
        decision.reason(),
        lossRatio,
        (int) storeUnsigned(score, POLICY_SCORE_DIGITS),
        accumulationExceeded);
  }

  static UnderwritingDecisionCode decide(long score) {
    if (score < ACCEPT_BELOW) {
      return UnderwritingDecisionCode.ACCEPT;
    }
    if (score < REFER_SENIOR_BELOW) {
      return UnderwritingDecisionCode.REFER_SENIOR;
    }
    if (score < REFER_MANAGER_BELOW) {
      return UnderwritingDecisionCode.REFER_MANAGER;
    }
    return UnderwritingDecisionCode.DECLINE;
  }

  private static boolean exceeds(BigDecimal value, long whole) {
    return value.compareTo(CobolArithmetic.whole(whole)) > 0;
  }
}
