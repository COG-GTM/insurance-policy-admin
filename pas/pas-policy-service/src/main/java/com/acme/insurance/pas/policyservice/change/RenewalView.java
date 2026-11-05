package com.acme.insurance.pas.policyservice.change;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Response of a renewal: the renewed policy term and how its premium was priced. */
public record RenewalView(
    String policyNumber,
    String policyStatus,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    BigDecimal previousPremium,
    BigDecimal totalPremium,
    BigDecimal rateChangePct,
    boolean rateCapApplied,
    int renewalCount,
    String uwStatus) {

  static RenewalView from(RenewalService.Renewal r) {
    return new RenewalView(
        r.policy().policyNumber(),
        r.policy().policyStatusCode(),
        r.policy().effectiveDate(),
        r.policy().expiryDate(),
        r.quote().previousPremium(),
        r.quote().newPremium(),
        r.quote().rateChangePct(),
        r.quote().capApplied(),
        r.quote().renewalCount(),
        r.policy().uwStatusCode());
  }
}
