package com.acme.insurance.pas.policyservice.change;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Response of an endorsement: the ENDORSEMENTS row, its pro-rata basis and the new premium. */
public record EndorsementView(
    String policyNumber,
    int endorsementSeq,
    String endorsementType,
    LocalDate effectiveDate,
    String description,
    long daysInTerm,
    long daysRemaining,
    BigDecimal proRataFactor,
    BigDecimal premiumAdjustment,
    BigDecimal totalPremium,
    String policyStatus) {

  static EndorsementView from(EndorsementService.EndorsementOutcome o) {
    return new EndorsementView(
        o.endorsement().policyNumber(),
        o.endorsement().endorsementSeq(),
        o.endorsement().endorsementTypeCode(),
        o.endorsement().effectiveDate(),
        o.endorsement().description(),
        o.proRata().daysInTerm(),
        o.proRata().daysRemaining(),
        o.proRata().factor(),
        o.proRata().adjustment(),
        o.totalPremium(),
        o.policyStatus());
  }
}
