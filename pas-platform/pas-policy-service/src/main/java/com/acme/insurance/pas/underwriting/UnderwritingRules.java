package com.acme.insurance.pas.underwriting;

import com.acme.insurance.pas.common.cobol.CobolArithmetic;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Implements the full-score UNDWRT decision before the AB-528 copybook bridge's 3-digit truncation.
 */
public final class UnderwritingRules {
  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

  private UnderwritingRules() {}

  public static UnderwritingDecision decide(
      UnderwritingInput input, UnderwritingThresholds thresholds) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(thresholds, "thresholds");

    BigDecimal score =
        input.creditScore() == null || input.creditScore() <= 0
            ? BigDecimal.valueOf(thresholds.noScoreBase())
            // Q-19 preserves the COBOL unsigned intermediate store before division.
            : CobolArithmetic.storeUnsigned(
                CobolArithmetic.storeUnsigned(
                        BigDecimal.valueOf(thresholds.creditCeiling() - input.creditScore()), 4, 0)
                    .divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN),
                4,
                0);

    List<String> factorReasons = new ArrayList<>();
    if ("U".equals(input.riskTier())) {
      score = add(score, thresholds.substandardAdjustment());
      factorReasons.add("SUBSTANDARD RISK TIER");
    } else if ("P".equals(input.riskTier())) {
      score = add(score, thresholds.preferredAdjustment());
      factorReasons.add("PREFERRED RISK TIER");
    }
    if (input.claimCount() > thresholds.firstClaimThreshold()) {
      score = add(score, thresholds.firstClaimAdjustment());
      factorReasons.add("CLAIMS IN 5 YEARS > " + thresholds.firstClaimThreshold());
    }
    if (input.claimCount() > thresholds.secondClaimThreshold()) {
      score = add(score, thresholds.secondClaimAdjustment());
      factorReasons.add("CLAIMS IN 5 YEARS > " + thresholds.secondClaimThreshold());
    }

    BigDecimal lossRatio = BigDecimal.ZERO.setScale(2);
    if (input.totalPremium() != null && input.totalPremium().signum() > 0) {
      BigDecimal incurred = input.totalIncurred() == null ? BigDecimal.ZERO : input.totalIncurred();
      lossRatio = incurred.multiply(ONE_HUNDRED).divide(input.totalPremium(), 2, RoundingMode.DOWN);
      if (lossRatio.compareTo(BigDecimal.valueOf(thresholds.lossRatioThreshold())) > 0) {
        score = add(score, thresholds.lossRatioAdjustment());
        factorReasons.add("LOSS RATIO > " + thresholds.lossRatioThreshold());
      }
    }

    BigDecimal limit = input.coverageLimit() == null ? BigDecimal.ZERO : input.coverageLimit();
    if (limit.compareTo(BigDecimal.valueOf(thresholds.firstLimitThreshold())) > 0) {
      score = add(score, thresholds.firstLimitAdjustment());
      factorReasons.add("COVERAGE LIMIT > " + thresholds.firstLimitThreshold());
    }
    if (limit.compareTo(BigDecimal.valueOf(thresholds.secondLimitThreshold())) > 0) {
      score = add(score, thresholds.secondLimitAdjustment());
      factorReasons.add("COVERAGE LIMIT > " + thresholds.secondLimitThreshold());
    }

    boolean accumulationExceeded =
        input.branchAccumulatedLimit() != null
            && input
                    .branchAccumulatedLimit()
                    .compareTo(BigDecimal.valueOf(thresholds.accumulationThreshold()))
                > 0;
    if (accumulationExceeded) {
      score = add(score, thresholds.accumulationAdjustment());
    }

    int riskScore = score.intValueExact();
    UnderwritingDecision.Code code;
    String decisionReason;
    if (riskScore < thresholds.seniorReferral()) {
      code = UnderwritingDecision.Code.AP;
      decisionReason = "AUTO-ACCEPTED: LOW RISK";
    } else if (riskScore < thresholds.managerReferral()) {
      code = UnderwritingDecision.Code.RS;
      decisionReason = "REFERRED TO SENIOR UNDERWRITER";
    } else if (riskScore < thresholds.autoDecline()) {
      code = UnderwritingDecision.Code.RM;
      decisionReason = "REFERRED TO UW MANAGER";
    } else {
      code = UnderwritingDecision.Code.DC;
      decisionReason = "AUTO-DECLINED: HIGH RISK";
    }
    List<String> reasons = new ArrayList<>();
    reasons.add(decisionReason);
    reasons.addAll(factorReasons);
    if (accumulationExceeded) {
      reasons.add("ACCUMULATION LIMIT EXCEEDED FOR BRANCH " + input.branchCode());
    }
    return new UnderwritingDecision(code, riskScore, reasons, lossRatio);
  }

  private static BigDecimal add(BigDecimal score, int adjustment) {
    return CobolArithmetic.storeUnsigned(score.add(BigDecimal.valueOf(adjustment)), 4, 0);
  }
}
