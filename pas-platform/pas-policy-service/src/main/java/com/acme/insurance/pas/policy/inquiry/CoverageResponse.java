package com.acme.insurance.pas.policy.inquiry;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.math.BigDecimal;

@JsonPropertyOrder({
  "policyNumber",
  "sequenceNum",
  "coverageType",
  "description",
  "coverageLimit",
  "deductible",
  "premium",
  "effectiveDate",
  "expiryDate",
  "status",
  "coinsurancePct",
  "ratingTerritory",
  "classCode"
})
public record CoverageResponse(
    String policyNumber,
    int sequenceNum,
    String coverageType,
    String description,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal premium,
    String effectiveDate,
    String expiryDate,
    String status,
    int coinsurancePct,
    String ratingTerritory,
    String classCode) {}
