package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * PREMBAT {@code 3200-CALCULATE-PREMIUM}: base premium by policy type, then territory, class and
 * experience factors, tax on the modified premium and a flat surcharge.
 *
 * <p>COBOL {@code COMPUTE} without {@code ROUNDED} truncates into the receiving field, so every
 * step is cut to cents with {@link RoundingMode#DOWN} (quirk Q-06). Parity with the COBOL program
 * is proven by the AB-520 characterization harness ({@code make parity}).
 */
public final class PremiumCalculator {

  private static final int CENTS = 2;

  private final RatingTable table;

  public PremiumCalculator(RatingTable table) {
    this.table = Objects.requireNonNull(table, "table");
  }

  /**
   * Rates one policy.
   *
   * @param policyType the 3-character POLICY_TYPE code; it must match exactly (no trimming or case
   *     folding), anything else takes the {@code WHEN OTHER} base
   */
  public PremiumBreakdown calculate(String policyType, RatingFactors factors) {
    Objects.requireNonNull(factors, "factors");
    BigDecimal base = table.baseRate(policyType);
    BigDecimal territoryPremium = cents(base.multiply(factors.territoryFactor()));
    BigDecimal classPremium = cents(territoryPremium.multiply(factors.classFactor()));
    BigDecimal modifiedPremium = cents(classPremium.multiply(factors.experienceMod()));
    BigDecimal tax = cents(modifiedPremium.multiply(table.taxRate()));
    BigDecimal surcharge = table.surcharge();
    BigDecimal total = cents(modifiedPremium.add(tax).add(surcharge));
    return new PremiumBreakdown(
        base, factors, territoryPremium, classPremium, modifiedPremium, tax, surcharge, total);
  }

  private static BigDecimal cents(BigDecimal amount) {
    return amount.setScale(CENTS, RoundingMode.DOWN);
  }
}
