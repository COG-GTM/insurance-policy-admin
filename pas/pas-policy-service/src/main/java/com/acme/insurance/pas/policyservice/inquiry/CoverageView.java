package com.acme.insurance.pas.policyservice.inquiry;

import com.acme.insurance.pas.domain.Coverage;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One element of {@code GET /api/v1/policies/{policyNumber}/coverages}, as the facade returned. */
public record CoverageView(
    String policyNumber,
    int sequenceNum,
    String coverageType,
    String description,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal premium,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String status,
    int coinsurancePct,
    String ratingTerritory,
    String classCode) {

  static CoverageView from(Coverage c) {
    return new CoverageView(
        c.policyNumber(),
        c.sequenceNum(),
        c.coverageTypeCode(),
        c.description(),
        c.coverageLimit(),
        c.deductible(),
        c.premium(),
        c.effectiveDate(),
        c.expiryDate(),
        c.statusCode(),
        PolicyView.orZero(c.coinsurancePct()),
        c.ratingTerritory(),
        c.classCode());
  }
}
