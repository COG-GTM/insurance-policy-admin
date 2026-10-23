package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record Endorsement(
    String policyNumber,
    Integer endorsementSeq,
    String endorsementType,
    LocalDate effectiveDate,
    String description,
    BigDecimal premiumAdjustment,
    OffsetDateTime processedDate,
    String processedBy) {
  public Endorsement {
    premiumAdjustment = Scales.normalize(premiumAdjustment, 2);
    processedDate = Scales.utc(processedDate);
  }
}
