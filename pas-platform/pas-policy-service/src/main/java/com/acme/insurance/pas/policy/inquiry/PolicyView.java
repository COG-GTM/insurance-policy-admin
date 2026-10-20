package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * {@code GET /api/v1/policies/{policyNumber}} body, field-for-field compatible with the {@code
 * java-facade} {@code Policy} bean (compat/api/snapshots/policies-v1.json): CHAR columns trimmed,
 * dates as {@code yyyy-MM-dd}, numbers as JSON numbers, {@code lastUpdated} as epoch millis, nulls
 * serialised.
 */
public record PolicyView(
    String policyNumber,
    String policyType,
    String policyStatus,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate effectiveDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate expiryDate,
    String policyholderId,
    String agentCode,
    String branchCode,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate inceptionDate,
    int renewalCount,
    String uwStatus,
    int riskScore,
    String webIndicator,
    String apiFlag,
    Long lastUpdated,
    String updatedBy) {

  static PolicyView from(PolicyEntity p) {
    return new PolicyView(
        Trim.of(p.getPolicyNumber()),
        Trim.of(p.getPolicyType()),
        Trim.of(p.getPolicyStatus()),
        p.getEffectiveDate(),
        p.getExpiryDate(),
        Trim.of(p.getPolicyholderId()),
        Trim.of(p.getAgentCode()),
        Trim.of(p.getBranchCode()),
        p.getTotalPremium(),
        p.getDeductible(),
        p.getCoverageLimit(),
        p.getInceptionDate(),
        Trim.primitive(p.getRenewalCount()),
        Trim.of(p.getUwStatus()),
        Trim.primitive(p.getRiskScore()),
        Trim.of(p.getWebIndicator()),
        Trim.of(p.getApiFlag()),
        epochMillis(p.getLastUpdated()),
        Trim.of(p.getUpdatedBy()));
  }

  private static Long epochMillis(LocalDateTime value) {
    return value == null ? null : value.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
  }
}
