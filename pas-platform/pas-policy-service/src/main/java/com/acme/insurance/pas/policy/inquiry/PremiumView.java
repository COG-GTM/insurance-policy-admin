package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.policy.persistence.PremiumEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One PREMIUMS row as the Broker Portal reads it (AB-528); rating internals are left out. */
public record PremiumView(
    int coverageSeq,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate termEffectiveDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate termExpiryDate,
    BigDecimal totalPremium,
    String installmentCode,
    BigDecimal installmentAmt,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate calcDate) {

  public static PremiumView from(PremiumEntity p) {
    return new PremiumView(
        p.getId().getCoverageSeq(),
        p.getId().getTermEffectiveDate(),
        p.getTermExpiryDate(),
        p.getTotalPremium(),
        Trim.of(p.getInstallmentCode()),
        p.getInstallmentAmt(),
        p.getCalcDate());
  }
}
