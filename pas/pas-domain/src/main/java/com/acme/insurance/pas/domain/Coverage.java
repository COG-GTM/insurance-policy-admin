package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** ACMEINS.COVERAGES row and COVERAGE-RECORD copybook. */
public record Coverage(
    String policyNumber,
    int sequenceNum,
    String coverageTypeCode,
    String description,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal premium,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String statusCode,
    Integer coinsurancePct,
    String ratingTerritory,
    String classCode) {

  public Coverage {
    text(required(policyNumber, "policyNumber"), 12, "policyNumber");
    text(coverageTypeCode, 4, "coverageTypeCode");
    text(description, 40, "description");
    coverageLimit = decimal(coverageLimit, 13, 2, "coverageLimit");
    deductible = decimal(deductible, 9, 2, "deductible");
    premium = decimal(premium, 11, 2, "premium");
    text(statusCode, 2, "statusCode");
    text(ratingTerritory, 6, "ratingTerritory");
    text(classCode, 5, "classCode");
  }

  public Optional<CoverageType> coverageType() {
    return CoverageType.fromCode(coverageTypeCode);
  }

  public Optional<CoverageStatus> status() {
    return CoverageStatus.fromCode(statusCode);
  }
}
