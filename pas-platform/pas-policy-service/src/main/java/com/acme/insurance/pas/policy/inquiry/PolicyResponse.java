package com.acme.insurance.pas.policy.inquiry;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.math.BigDecimal;

@JsonPropertyOrder({
  "policyNumber",
  "policyType",
  "policyStatus",
  "effectiveDate",
  "expiryDate",
  "policyholderId",
  "agentCode",
  "branchCode",
  "totalPremium",
  "deductible",
  "coverageLimit",
  "inceptionDate",
  "renewalCount",
  "uwStatus",
  "riskScore",
  "webIndicator",
  "apiFlag",
  "lastUpdated",
  "updatedBy"
})
public record PolicyResponse(
    String policyNumber,
    String policyType,
    String policyStatus,
    String effectiveDate,
    String expiryDate,
    String policyholderId,
    String agentCode,
    String branchCode,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit,
    String inceptionDate,
    int renewalCount,
    String uwStatus,
    int riskScore,
    String webIndicator,
    String apiFlag,
    long lastUpdated,
    String updatedBy) {}
