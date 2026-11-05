package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ACMEINS.POLICIES row and POLICY-RECORD copybook. Codes stay raw strings (quirk Q-35); the typed
 * accessors decode the known values. Money uses the DB2 scale (DECIMAL(p,2)).
 */
public record Policy(
    String policyNumber,
    String policyTypeCode,
    String policyStatusCode,
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
    String uwStatusCode,
    Integer riskScore,
    String webIndicator,
    String apiFlag,
    LocalDateTime lastUpdated,
    String updatedBy) {

  public Policy {
    text(required(policyNumber, "policyNumber"), 12, "policyNumber");
    text(policyTypeCode, 3, "policyTypeCode");
    text(policyStatusCode, 2, "policyStatusCode");
    text(policyholderId, 10, "policyholderId");
    text(agentCode, 6, "agentCode");
    text(branchCode, 4, "branchCode");
    totalPremium = decimal(totalPremium, 11, 2, "totalPremium");
    deductible = decimal(deductible, 9, 2, "deductible");
    coverageLimit = decimal(coverageLimit, 13, 2, "coverageLimit");
    text(uwStatusCode, 2, "uwStatusCode");
    text(webIndicator, 1, "webIndicator");
    text(apiFlag, 1, "apiFlag");
    text(updatedBy, 8, "updatedBy");
  }

  public Optional<PolicyType> policyType() {
    return PolicyType.fromCode(policyTypeCode);
  }

  public Optional<PolicyStatus> policyStatus() {
    return PolicyStatus.fromCode(policyStatusCode);
  }

  public Optional<UnderwritingStatus> uwStatus() {
    return UnderwritingStatus.fromCode(uwStatusCode);
  }
}
