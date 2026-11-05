package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/** ACMEINS.ENDORSEMENTS row (POLEND 4000-WRITE-ENDORSEMENT). */
public record Endorsement(
    String policyNumber,
    int endorsementSeq,
    String endorsementTypeCode,
    LocalDate effectiveDate,
    String description,
    BigDecimal premiumAdjustment,
    LocalDateTime processedDate,
    String processedBy) {

  public Endorsement {
    text(required(policyNumber, "policyNumber"), 12, "policyNumber");
    text(required(endorsementTypeCode, "endorsementTypeCode"), 3, "endorsementTypeCode");
    required(effectiveDate, "effectiveDate");
    text(description, 100, "description");
    premiumAdjustment = decimal(premiumAdjustment, 11, 2, "premiumAdjustment");
    text(required(processedBy, "processedBy"), 8, "processedBy");
  }

  public Optional<EndorsementType> endorsementType() {
    return EndorsementType.fromCode(endorsementTypeCode);
  }
}
