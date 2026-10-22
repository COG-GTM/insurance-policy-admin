package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PremiumBreakdown(
    String policyType,
    String policyTypeKey,
    LocalDate ratingDate,
    FactorMode factorMode,
    String factorSet,
    LocalDate parametersEffectiveDate,
    BigDecimal basePremium,
    BigDecimal territoryFactor,
    BigDecimal classFactor,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal territoryPremium,
    BigDecimal classPremium,
    BigDecimal experiencePremium,
    BigDecimal ratedPremium,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal surchargeAmount,
    BigDecimal totalPremium) {
  public BigDecimal premiumRecordBaseRate() {
    return CobolPic.store(basePremium, 7, 4, "PREM-BASE-RATE");
  }
}
