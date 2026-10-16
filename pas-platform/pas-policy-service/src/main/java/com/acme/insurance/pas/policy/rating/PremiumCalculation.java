package com.acme.insurance.pas.policy.rating;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Result of a {@link PremiumCalculator} run (the fields PREMBAT writes to PREMIUMS). */
public record PremiumCalculation(
    int coverageSeq,
    LocalDate termEffectiveDate,
    LocalDate termExpiryDate,
    BigDecimal baseRate,
    BigDecimal territoryPremium,
    BigDecimal classPremium,
    BigDecimal modifiedPremium,
    BigDecimal tax,
    BigDecimal surcharge,
    BigDecimal total) {}
