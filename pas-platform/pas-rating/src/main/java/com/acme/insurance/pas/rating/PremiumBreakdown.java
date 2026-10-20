package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.common.domain.PremiumRecord;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Full result of one rating: every input factor and intermediate amount of PREMBAT {@code
 * 3200-CALCULATE-PREMIUM}, plus which tables produced it, so the row can be written to PREMIUMS and
 * audited.
 *
 * @param coverageSeq always 1: PREMBAT writes one PREMIUMS row per policy (quirk Q-23)
 * @param territoryPremium WS-TERR-PREMIUM = base x territory factor, truncated
 * @param classPremium WS-CLASS-PREMIUM = territory premium x class factor, truncated
 * @param modifiedPremium WS-MOD-PREMIUM = class premium x experience mod, truncated
 * @param scheduleMod written to PREMIUMS; PREMBAT never applies it (must be neutral)
 * @param discountPct written to PREMIUMS; PREMBAT never applies it (must be neutral)
 * @param ratingDate as-of date of the tables (PREMBAT run date, written as CALC_DATE)
 * @param factorSet territory/class factor set used ({@code PARITY} / {@code FILED})
 * @param rateVersion {@code pas.rating_parameters.rate_version} used
 */
public record PremiumBreakdown(
    String policyType,
    String territoryCode,
    String classCode,
    int coverageSeq,
    LocalDate termEffectiveDate,
    LocalDate termExpiryDate,
    BigDecimal baseRate,
    BigDecimal territoryFactor,
    BigDecimal territoryPremium,
    BigDecimal classFactor,
    BigDecimal classPremium,
    BigDecimal experienceMod,
    BigDecimal modifiedPremium,
    BigDecimal scheduleMod,
    BigDecimal discountPct,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal surchargeAmount,
    BigDecimal totalPremium,
    LocalDate ratingDate,
    String factorSet,
    String rateVersion) {

  /** PREMBAT writes INSTALLMENT_CODE 'AN' and leaves INSTALLMENT_AMT at its default. */
  public static final String ANNUAL_INSTALLMENT = "AN";

  /** Audit identifier of the tables used, e.g. {@code PREMBAT-2019/PARITY}. */
  public String ratingVersion() {
    return rateVersion + "/" + factorSet;
  }

  /** The PREMIUMS row PREMBAT {@code 3300-WRITE-PREMIUM-RECORD} would insert. */
  public PremiumRecord toPremiumRecord(String policyNumber, String calcBy) {
    return new PremiumRecord(
        policyNumber,
        coverageSeq,
        termEffectiveDate,
        termExpiryDate,
        baseRate.setScale(4),
        territoryFactor.setScale(4),
        classFactor.setScale(4),
        experienceMod.setScale(4),
        scheduleMod.setScale(4),
        discountPct.setScale(2),
        surchargeAmount,
        taxAmount,
        totalPremium,
        ANNUAL_INSTALLMENT,
        BigDecimal.ZERO.setScale(2),
        ratingDate,
        calcBy);
  }
}
