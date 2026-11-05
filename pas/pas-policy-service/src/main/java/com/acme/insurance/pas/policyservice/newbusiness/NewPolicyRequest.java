package com.acme.insurance.pas.policyservice.newbusiness;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create-policy input: the POLNMAP fields POLNEW reads. The premium is stored as given, not rated
 * (quirk Q-12).
 */
public record NewPolicyRequest(
    String policyType,
    String policyholderId,
    String agentCode,
    String branchCode,
    LocalDate effectiveDate,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit) {}
