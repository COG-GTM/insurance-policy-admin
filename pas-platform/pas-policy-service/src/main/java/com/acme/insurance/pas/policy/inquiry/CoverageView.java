package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.policy.persistence.CoverageEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One element of {@code GET /api/v1/policies/{policyNumber}/coverages}; facade-compatible. */
public record CoverageView(
    String policyNumber,
    int sequenceNum,
    String coverageType,
    String description,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal premium,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate effectiveDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate expiryDate,
    String status,
    int coinsurancePct,
    String ratingTerritory,
    String classCode) {

  static CoverageView from(CoverageEntity c) {
    return new CoverageView(
        Trim.of(c.getId().getPolicyNumber()),
        Trim.primitive(c.getId().getSequenceNum()),
        Trim.of(c.getCoverageType()),
        Trim.of(c.getDescription()),
        c.getCoverageLimit(),
        c.getDeductible(),
        c.getPremium(),
        c.getEffectiveDate(),
        c.getExpiryDate(),
        Trim.of(c.getStatus()),
        Trim.primitive(c.getCoinsurancePct()),
        Trim.of(c.getRatingTerritory()),
        Trim.of(c.getClassCode()));
  }
}
