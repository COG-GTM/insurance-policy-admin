package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Coverage(
    String policyNumber,
    Integer sequenceNum,
    Coded<CoverageType> coverageType,
    String description,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal premium,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    Coded<CoverageStatus> status,
    Integer coinsurancePct,
    String ratingTerritory,
    String classCode) {
  public Coverage {
    coverageLimit = Scales.normalize(coverageLimit, 2);
    deductible = Scales.normalize(deductible, 2);
    premium = Scales.normalize(premium, 2);
  }
}
