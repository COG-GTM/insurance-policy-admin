package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Premium(
    String policyNumber,
    Integer coverageSeq,
    LocalDate termEffectiveDate,
    LocalDate termExpiryDate,
    BigDecimal baseRate,
    BigDecimal territoryFactor,
    BigDecimal classFactor,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal discountPct,
    BigDecimal surchargeAmt,
    BigDecimal taxAmt,
    BigDecimal totalPremium,
    Coded<InstallmentCode> installmentCode,
    BigDecimal installmentAmt,
    LocalDate calcDate,
    String calcBy) {
  public Premium {
    baseRate = Scales.normalize(baseRate, 4);
    territoryFactor = Scales.normalize(territoryFactor, 4);
    classFactor = Scales.normalize(classFactor, 4);
    experienceMod = Scales.normalize(experienceMod, 4);
    scheduleMod = Scales.normalize(scheduleMod, 4);
    discountPct = Scales.normalize(discountPct, 2);
    surchargeAmt = Scales.normalize(surchargeAmt, 2);
    taxAmt = Scales.normalize(taxAmt, 2);
    totalPremium = Scales.normalize(totalPremium, 2);
    installmentAmt = Scales.normalize(installmentAmt, 2);
  }
}
