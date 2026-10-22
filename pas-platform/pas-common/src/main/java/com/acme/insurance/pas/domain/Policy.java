package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record Policy(
    String policyNumber,
    Coded<PolicyType> policyType,
    Coded<PolicyStatus> policyStatus,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String policyholderId,
    String agentCode,
    String branchCode,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit,
    LocalDate inceptionDate,
    Integer renewalCount,
    Coded<UnderwritingStatus> uwStatus,
    Integer riskScore,
    Coded<YesNo> webIndicator,
    Coded<YesNo> apiFlag,
    OffsetDateTime lastUpdated,
    String updatedBy) {
  public Policy {
    totalPremium = Scales.normalize(totalPremium, 2);
    deductible = Scales.normalize(deductible, 2);
    coverageLimit = Scales.normalize(coverageLimit, 2);
    lastUpdated = Scales.utc(lastUpdated);
  }
}
