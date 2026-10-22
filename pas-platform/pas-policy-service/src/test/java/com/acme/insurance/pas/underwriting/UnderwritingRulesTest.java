package com.acme.insurance.pas.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UnderwritingRulesTest {
  private final UnderwritingThresholds thresholds = UnderwritingThresholds.legacyDefaults();

  @Test
  void appliesScoreBoundaries() {
    assertThat(decide(302, "S", 0, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(299);
    assertThat(decide(300, "S", 0, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(300);
    assertThat(decide(298, "S", 0, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(301);
    assertThat(decide(100, "U", 0, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(600);
  }

  @Test
  void treatsNullAndNonpositiveCreditAsNoScore() {
    assertThat(decide(null, "S", 0, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(400);
    assertThat(decide(-5, "S", 0, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(400);
  }

  @Test
  void appliesTierAndClaimAdjustments() {
    assertThat(decide(850, "P", 0, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(75);
    assertThat(decide(1, "U", 4, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(799);
    assertThat(decide(1, "U", 6, "0.00", "100000.00", "1000000.00").riskScore()).isEqualTo(999);
  }

  @Test
  void appliesLossRatioAndLimitBoundaries() {
    assertThat(decide(302, "S", 0, "800.00", "5000000.00", "1000000.00").riskScore())
        .isEqualTo(299);
    assertThat(decide(302, "S", 0, "800.10", "5000000.00", "1000000.00").riskScore())
        .isEqualTo(499);
    assertThat(decide(302, "S", 0, "0.00", "10000000.01", "1000000.00").riskScore()).isEqualTo(599);
  }

  @Test
  void keepsLossRatioUncappedAndAppendsAccumulationReason() {
    UnderwritingDecision decision = decide(302, "S", 0, "10500.00", "100000.00", "500000000.01");
    assertThat(decision.lossRatio()).isEqualByComparingTo("1050.00");
    assertThat(decision.reasons())
        .containsExactly(
            "REFERRED TO UW MANAGER",
            "LOSS RATIO > 80",
            "ACCUMULATION LIMIT EXCEEDED FOR BRANCH B001");
  }

  @Test
  void accumulatesReasonsForEveryAppliedAdjustment() {
    UnderwritingDecision decision =
        UnderwritingRules.decide(
            new UnderwritingInput(
                100,
                "U",
                new BigDecimal("1000.00"),
                new BigDecimal("10000000.01"),
                new BigDecimal("800.10"),
                new BigDecimal("500000000.01"),
                6,
                "B001"),
            thresholds);

    assertThat(decision.reasons())
        .containsExactly(
            "AUTO-DECLINED: HIGH RISK",
            "SUBSTANDARD RISK TIER",
            "CLAIMS IN 5 YEARS > 3",
            "CLAIMS IN 5 YEARS > 5",
            "LOSS RATIO > 80",
            "COVERAGE LIMIT > 5000000",
            "COVERAGE LIMIT > 10000000",
            "ACCUMULATION LIMIT EXCEEDED FOR BRANCH B001");
  }

  private UnderwritingDecision decide(
      Integer creditScore,
      String riskTier,
      int claimCount,
      String totalIncurred,
      String coverageLimit,
      String branchAccumulatedLimit) {
    return UnderwritingRules.decide(
        new UnderwritingInput(
            creditScore,
            riskTier,
            new BigDecimal("1000.00"),
            new BigDecimal(coverageLimit),
            new BigDecimal(totalIncurred),
            new BigDecimal(branchAccumulatedLimit),
            claimCount,
            "B001"),
        thresholds);
  }
}
