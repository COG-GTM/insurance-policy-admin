package com.acme.insurance.pas.policy.underwriting;

import java.math.BigDecimal;

public record UnderwritingInput(
    Integer creditScore,
    String riskTier,
    BigDecimal totalPremium,
    BigDecimal coverageLimit,
    BigDecimal totalIncurred,
    BigDecimal branchAccumulatedLimit,
    int claimCount,
    String branchCode) {}
