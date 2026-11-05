package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * PREMBAT 3200-CALCULATE-PREMIUM. Each step is truncated to cents like a COBOL COMPUTE without
 * ROUNDED into a V99 field (quirk Q-06): base x territory, x class, x experience mod, then tax on
 * the modified premium, then modified premium + tax + surcharge. Each result must fit its COBOL
 * receiving field or rating fails (quirk Q-522-1).
 */
public final class PremiumCalculator {

  private static final int CENTS = 2;

  /** {@code S9(09)V99}: WS-BASE/TERR/CLASS/MOD/FINAL-PREMIUM. */
  private static final int PREMIUM_DIGITS = 9;

  /** {@code S9(07)V99}: WS-TAX-AMOUNT, WS-SURCHARGE. */
  private static final int TAX_DIGITS = 7;

  private final RatingTable table;

  public PremiumCalculator(RatingTable table) {
    this.table = Objects.requireNonNull(table, "table");
  }

  public PremiumBreakdown calculate(String policyType, RatingFactors factors) {
    Objects.requireNonNull(factors, "factors");
    BigDecimal base = fit(table.baseRate(policyType), PREMIUM_DIGITS, "WS-BASE-PREMIUM");
    BigDecimal territoryPremium =
        cents(base.multiply(factors.territoryFactor()), PREMIUM_DIGITS, "WS-TERR-PREMIUM");
    BigDecimal classPremium =
        cents(territoryPremium.multiply(factors.classFactor()), PREMIUM_DIGITS, "WS-CLASS-PREMIUM");
    BigDecimal modifiedPremium =
        cents(classPremium.multiply(factors.experienceMod()), PREMIUM_DIGITS, "WS-MOD-PREMIUM");
    BigDecimal taxRate = table.taxRate();
    BigDecimal tax = cents(modifiedPremium.multiply(taxRate), TAX_DIGITS, "WS-TAX-AMOUNT");
    BigDecimal surcharge = fit(table.surcharge(), TAX_DIGITS, "WS-SURCHARGE");
    BigDecimal total =
        cents(modifiedPremium.add(tax).add(surcharge), PREMIUM_DIGITS, "WS-FINAL-PREMIUM");
    return new PremiumBreakdown(
        base,
        factors,
        territoryPremium,
        classPremium,
        modifiedPremium,
        taxRate,
        tax,
        surcharge,
        total);
  }

  private static BigDecimal cents(BigDecimal amount, int integerDigits, String field) {
    return fit(amount.setScale(CENTS, RoundingMode.DOWN), integerDigits, field);
  }

  private static BigDecimal fit(BigDecimal amount, int integerDigits, String field) {
    if (amount.precision() - amount.scale() > integerDigits) {
      throw new RatingOverflowException(
          field + " needs more than " + integerDigits + " integer digits: " + amount);
    }
    return amount;
  }
}
