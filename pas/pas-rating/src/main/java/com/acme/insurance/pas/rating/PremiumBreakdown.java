package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.InstallmentCode;
import com.acme.insurance.pas.domain.Premium;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Every value of one PREMBAT 3200-CALCULATE-PREMIUM run, for the PREMIUMS row and for audit: the
 * base, the factors applied, each intermediate premium ({@code WS-TERR-PREMIUM}, {@code
 * WS-CLASS-PREMIUM}, {@code WS-MOD-PREMIUM}), the tax rate and amount, the surcharge and the total.
 */
public record PremiumBreakdown(
    BigDecimal basePremium,
    RatingFactors factors,
    BigDecimal territoryPremium,
    BigDecimal classPremium,
    BigDecimal modifiedPremium,
    BigDecimal taxRate,
    BigDecimal tax,
    BigDecimal surcharge,
    BigDecimal totalPremium) {

  /**
   * The PREMIUMS row PREMBAT 3300-WRITE-PREMIUM-RECORD inserts for this breakdown: annual
   * installment, INSTALLMENT_AMT left null, base rate widened to 4 decimal places.
   */
  public Premium toPremium(
      String policyNumber,
      int coverageSeq,
      LocalDate termEffectiveDate,
      LocalDate termExpiryDate,
      LocalDate calcDate,
      String calcBy) {
    return new Premium(
        policyNumber,
        coverageSeq,
        termEffectiveDate,
        termExpiryDate,
        basePremium.setScale(4, RoundingMode.UNNECESSARY),
        factors.territoryFactor(),
        factors.classFactor(),
        factors.experienceMod(),
        factors.scheduleMod(),
        factors.discountPct(),
        surcharge,
        tax,
        totalPremium,
        InstallmentCode.ANNUAL.code(),
        null,
        calcDate,
        calcBy);
  }
}
