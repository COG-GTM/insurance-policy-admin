package com.acme.insurance.pas.policy.underwriting;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Everything UNDWRT reads before scoring: the POLICIES/POLICY_HOLDERS join, the 5-year claims
 * aggregates (now from {@code ClaimsHistoryPort}) and the branch accumulation sum.
 */
public record RiskInputs(
    BigDecimal totalPremium,
    BigDecimal policyLimit,
    String branchCode,
    int creditScore,
    String riskTier,
    long claimCount5y,
    BigDecimal incurred5y,
    BigDecimal branchAccumulation) {

  public RiskInputs {
    Objects.requireNonNull(totalPremium, "totalPremium");
    Objects.requireNonNull(policyLimit, "policyLimit");
    Objects.requireNonNull(incurred5y, "incurred5y");
    Objects.requireNonNull(branchAccumulation, "branchAccumulation");
  }
}
