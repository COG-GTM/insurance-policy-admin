package com.acme.insurance.pas.policy.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Spot checks beyond the parity goldens (which cover every threshold ±1). */
class UnderwritingRiskScorerTest {

  static RiskInputs inputs(int credit, String tier, long claims, String incurred) {
    return new RiskInputs(
        new BigDecimal("1000.00"),
        new BigDecimal("100000.00"),
        "B001",
        credit,
        tier,
        claims,
        new BigDecimal(incurred),
        BigDecimal.ZERO);
  }

  @Test
  void worstCaseScoreStaysWithinFourDigitsAndPolicyKeepsModThousand() {
    RiskAssessment a =
        UnderwritingRiskScorer.assess(
            new RiskInputs(
                new BigDecimal("100.00"),
                new BigDecimal("99999999999.99"),
                "B001",
                1,
                "U",
                99_999,
                new BigDecimal("80.01"),
                new BigDecimal("9999999999999.99")));
    assertThat(a.riskScore()).isEqualTo(449 + 200 + 350 + 200 + 300 + 300);
    assertThat(a.policyRiskScore()).isEqualTo(799);
    assertThat(a.decision()).isEqualTo(UnderwritingDecisionCode.DECLINE);
    assertThat(a.accumulationExceeded()).isTrue();
  }

  @Test
  void creditAboveThreeDigitsIsTruncatedLikePic9of3() {
    assertThat(UnderwritingRiskScorer.assess(inputs(1300, "S", 0, "0")).riskScore()).isEqualTo(300);
  }

  @Test
  void referralCodes() {
    assertThat(UnderwritingDecisionCode.REFER_SENIOR.isReferral()).isTrue();
    assertThat(UnderwritingDecisionCode.REFER_MANAGER.isReferral()).isTrue();
    assertThat(UnderwritingDecisionCode.ACCEPT.isReferral()).isFalse();
    assertThat(UnderwritingDecisionCode.fromCode("DC ")).contains(UnderwritingDecisionCode.DECLINE);
  }
}
