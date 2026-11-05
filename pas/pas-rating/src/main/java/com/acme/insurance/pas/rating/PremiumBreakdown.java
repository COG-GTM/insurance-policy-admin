package com.acme.insurance.pas.rating;

import java.math.BigDecimal;

/**
 * Full premium breakdown, as written to PREMIUMS and needed for audit: base, factors, the
 * intermediate premiums after each factor, tax, surcharge and total.
 */
public record PremiumBreakdown(
    BigDecimal basePremium,
    RatingFactors factors,
    BigDecimal territoryPremium,
    BigDecimal classPremium,
    BigDecimal modifiedPremium,
    BigDecimal tax,
    BigDecimal surcharge,
    BigDecimal totalPremium) {}
