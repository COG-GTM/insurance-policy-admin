package com.acme.insurance.pas.policyservice.inquiry;

import com.acme.insurance.pas.domain.Policy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * {@code GET /api/v1/policies/{policyNumber}}, field for field the JSON the Spring Boot 1.5 facade
 * returned (compat/api/snapshots/policies-v1.json, Q-524-1): dates as {@code yyyy-MM-dd}, {@code
 * lastUpdated} as epoch milliseconds, and the integer fields the facade held as primitives are 0
 * when the column is null.
 */
public record PolicyView(
    String policyNumber,
    String policyType,
    String policyStatus,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String policyholderId,
    String agentCode,
    String branchCode,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit,
    LocalDate inceptionDate,
    int renewalCount,
    String uwStatus,
    int riskScore,
    String webIndicator,
    String apiFlag,
    Long lastUpdated,
    String updatedBy) {

  static PolicyView from(Policy p, ZoneId timestampZone) {
    return new PolicyView(
        p.policyNumber(),
        p.policyTypeCode(),
        p.policyStatusCode(),
        p.effectiveDate(),
        p.expiryDate(),
        p.policyholderId(),
        p.agentCode(),
        p.branchCode(),
        p.totalPremium(),
        p.deductible(),
        p.coverageLimit(),
        p.inceptionDate(),
        orZero(p.renewalCount()),
        p.uwStatusCode(),
        orZero(p.riskScore()),
        p.webIndicator(),
        p.apiFlag(),
        p.lastUpdated() == null
            ? null
            : p.lastUpdated().atZone(timestampZone).toInstant().toEpochMilli(),
        p.updatedBy());
  }

  static int orZero(Integer value) {
    return value == null ? 0 : value;
  }
}
