package com.acme.insurance.pas.policy.newbusiness;

import java.math.BigDecimal;
import java.time.LocalDate;

public record NewBusinessRequest(
    String policyType,
    String policyholderId,
    LocalDate effectiveDate,
    String agentCode,
    String branchCode,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal totalPremium) {
  public NewBusinessRequest {
    deductible = deductible == null ? BigDecimal.ZERO.setScale(2) : deductible;
    totalPremium = totalPremium == null ? BigDecimal.ZERO.setScale(2) : totalPremium;
  }
}
