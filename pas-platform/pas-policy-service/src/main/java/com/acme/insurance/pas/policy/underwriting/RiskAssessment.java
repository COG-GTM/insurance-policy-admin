package com.acme.insurance.pas.policy.underwriting;

import java.math.BigDecimal;

/**
 * UNDWRT result.
 *
 * @param riskScore {@code WS-RISK-SCORE} PIC 9(4): stored in UNDERWRITING_DECISIONS and POLICIES
 * @param decision band decision from {@code 5000-RENDER-DECISION}
 * @param decisionReason stored reason; the accumulation text is always overwritten (Q-21 / golden
 *     {@code UNDWRT-ACCUM}), so this is the band reason
 * @param lossRatio {@code WS-LOSS-RATIO} PIC S9(3)V99 (truncated, high-order digits dropped)
 * @param policyRiskScore {@code POLICY-RISK-SCORE} PIC 9(3) on POLICY-RECORD (score mod 1000)
 * @param accumulationExceeded the branch accumulation surcharge applied (never shown by UNDWRT)
 */
public record RiskAssessment(
    int riskScore,
    UnderwritingDecisionCode decision,
    String decisionReason,
    BigDecimal lossRatio,
    int policyRiskScore,
    boolean accumulationExceeded) {}
