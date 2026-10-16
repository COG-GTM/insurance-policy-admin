package com.acme.insurance.pas.policy.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Faithful port of PREMBAT 3200-CALCULATE-PREMIUM as written today: hardcoded base rates by exact
 * 3-character policy type (anything else, including lowercase and blank, falls to the OTHER branch
 * at 1000.00), territory / class / experience factors of 1.00, a flat 3.50% tax, and a flat 25.00
 * regulatory surcharge.
 *
 * <p>COBOL COMPUTE without ROUNDED truncates, so all scaling uses {@link RoundingMode#DOWN} —
 * deliberately not {@code Money}, which is HALF_UP.
 *
 * <p>AB-522 makes this table-driven; do not extend it before then.
 */
public final class PremiumCalculator {

  private static final Map<String, BigDecimal> BASE_RATES =
      Map.of(
          "AUT", new BigDecimal("850.00"),
          "HOM", new BigDecimal("1200.00"),
          "COM", new BigDecimal("5000.00"),
          "LIF", new BigDecimal("400.00"),
          "HLT", new BigDecimal("3500.00"));

  private static final BigDecimal OTHER_BASE_RATE = new BigDecimal("1000.00");
  private static final BigDecimal TAX_RATE = new BigDecimal("0.0350");
  private static final BigDecimal SURCHARGE = new BigDecimal("25.00");

  public PremiumCalculation rate(PolicyTerm policy) {
    BigDecimal base = BASE_RATES.getOrDefault(policy.policyType(), OTHER_BASE_RATE);
    BigDecimal territory = base.multiply(BigDecimal.ONE).setScale(2, RoundingMode.DOWN);
    BigDecimal clazz = territory.multiply(BigDecimal.ONE).setScale(2, RoundingMode.DOWN);
    BigDecimal modified = clazz.multiply(BigDecimal.ONE).setScale(2, RoundingMode.DOWN);
    BigDecimal tax = modified.multiply(TAX_RATE).setScale(2, RoundingMode.DOWN);
    BigDecimal total = modified.add(tax).add(SURCHARGE);
    return new PremiumCalculation(
        1,
        policy.effectiveDate(),
        policy.expiryDate(),
        base,
        territory,
        clazz,
        modified,
        tax,
        SURCHARGE,
        total);
  }
}
