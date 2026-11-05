package com.acme.insurance.pas.underwriting;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What UNDWRT {@code 2000-READ-POLICY-DATA} selects: the policy joined to its holder. Null amounts
 * and a null credit score are read as zero (quirk Q-525-2: UNDWRT has no null indicators, so DB2
 * answers -305 and the transaction reports "not found").
 */
public record UnderwritingSubject(
    String policyNumber,
    String policyTypeCode,
    BigDecimal totalPremium,
    BigDecimal coverageLimit,
    String branchCode,
    String agentCode,
    String policyholderId,
    String custTypeCode,
    Integer creditScore,
    String riskTierCode) {

  public UnderwritingSubject {
    Objects.requireNonNull(policyNumber, "policyNumber");
    totalPremium = totalPremium == null ? BigDecimal.ZERO : totalPremium;
    coverageLimit = coverageLimit == null ? BigDecimal.ZERO : coverageLimit;
    creditScore = creditScore == null ? Integer.valueOf(0) : creditScore;
  }
}
