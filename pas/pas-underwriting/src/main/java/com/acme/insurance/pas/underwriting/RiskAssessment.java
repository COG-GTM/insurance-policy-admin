package com.acme.insurance.pas.underwriting;

import java.math.BigDecimal;

/**
 * Result of UNDWRT {@code 3000-CALCULATE-RISK-SCORE} and {@code 4000-CHECK-ACCUMULATION}.
 *
 * @param riskScore the score, never negative and never truncated (quirk Q-14)
 * @param claimCount claims in the look-back window
 * @param lossRatio incurred / premium * 100, truncated to 2 decimals but not to 3 integer digits
 *     (quirk Q-15); 0.00 when the premium is zero and the check is skipped
 * @param accumulationExceeded the branch's active limits are above the accumulation limit
 */
public record RiskAssessment(
    int riskScore, int claimCount, BigDecimal lossRatio, boolean accumulationExceeded) {}
