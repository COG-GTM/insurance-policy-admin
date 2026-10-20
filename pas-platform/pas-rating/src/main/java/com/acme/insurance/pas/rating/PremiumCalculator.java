package com.acme.insurance.pas.rating;

import java.math.BigDecimal;

/**
 * Port of PREMBAT {@code 3200-CALCULATE-PREMIUM}. Holds no rates: everything comes from the {@link
 * RatingSnapshot}. Each step stores into the same PIC as the COBOL working-storage field and, like
 * a COMPUTE without ROUNDED, truncates toward zero (deliberately not {@code Money}, which is
 * HALF_UP):
 *
 * <pre>
 *   WS-BASE-PREMIUM  S9(9)V99 = base rate (exact type, else catch-all)
 *   WS-TERR-PREMIUM  S9(9)V99 = base       x territory factor
 *   WS-CLASS-PREMIUM S9(9)V99 = territory  x class factor
 *   WS-MOD-PREMIUM   S9(9)V99 = class      x experience mod
 *   WS-TAX-AMOUNT    S9(7)V99 = modified   x tax rate
 *   WS-SURCHARGE     S9(7)V99 = flat surcharge
 *   WS-FINAL-PREMIUM S9(9)V99 = modified + tax + surcharge
 * </pre>
 *
 * PREMBAT writes the base into PREM-BASE-RATE S9(7)V9999, so base rates are also checked against
 * that PIC. Stateless and thread-safe.
 */
public final class PremiumCalculator {

  static final int AMOUNT_SCALE = 2;
  static final int RATE_SCALE = 4;

  public PremiumBreakdown rate(RatingSnapshot tables, RatingRequest request) {
    RatingParameters p = tables.parameters();
    requireNeutral(p);

    BigDecimal base =
        CobolDecimal.store(
            "WS-BASE-PREMIUM", tables.baseRate(request.policyType()), 9, AMOUNT_SCALE);
    CobolDecimal.store("PREM-BASE-RATE", base, 7, RATE_SCALE);
    BigDecimal territoryFactor =
        factor("PREM-TERRITORY-FACTOR", tables.territoryFactor(request.territoryCode()), 3);
    BigDecimal classFactor =
        factor("PREM-CLASS-FACTOR", tables.classFactor(request.classCode()), 3);
    BigDecimal experienceMod = factor("PREM-EXPERIENCE-MOD", p.experienceMod(), 3);
    BigDecimal taxRate = exact("WS-TAX-RATE", p.taxRate(), 1);

    BigDecimal territory =
        CobolDecimal.store("WS-TERR-PREMIUM", base.multiply(territoryFactor), 9, AMOUNT_SCALE);
    BigDecimal clazz =
        CobolDecimal.store("WS-CLASS-PREMIUM", territory.multiply(classFactor), 9, AMOUNT_SCALE);
    BigDecimal modified =
        CobolDecimal.store("WS-MOD-PREMIUM", clazz.multiply(experienceMod), 9, AMOUNT_SCALE);
    BigDecimal tax =
        CobolDecimal.store("WS-TAX-AMOUNT", modified.multiply(taxRate), 7, AMOUNT_SCALE);
    BigDecimal surcharge = amount("WS-SURCHARGE", p.surchargeAmount(), 7);
    BigDecimal total =
        CobolDecimal.store("WS-FINAL-PREMIUM", modified.add(tax).add(surcharge), 9, AMOUNT_SCALE);

    return new PremiumBreakdown(
        request.policyType(),
        request.territoryCode(),
        request.classCode(),
        1,
        request.termEffectiveDate(),
        request.termExpiryDate(),
        base,
        territoryFactor,
        territory,
        classFactor,
        clazz,
        experienceMod,
        modified,
        p.scheduleMod(),
        p.discountPct(),
        taxRate,
        tax,
        surcharge,
        total,
        tables.asOf(),
        tables.factorSet(),
        p.rateVersion());
  }

  private static BigDecimal factor(String field, BigDecimal value, int integerDigits) {
    if (value.signum() <= 0) {
      throw new RatingDataException(field + " must be positive, was " + value.toPlainString());
    }
    return exact(field, value, integerDigits);
  }

  /** A rate must fit its PIC exactly; a table value needing truncation is a data error. */
  private static BigDecimal exact(String field, BigDecimal value, int integerDigits) {
    if (value.signum() < 0) {
      throw new RatingDataException(field + " must not be negative, was " + value.toPlainString());
    }
    BigDecimal stored;
    try {
      stored = CobolDecimal.store(field, value, integerDigits, RATE_SCALE);
    } catch (RatingOverflowException e) {
      throw new RatingDataException(e.getMessage());
    }
    if (stored.compareTo(value) != 0) {
      throw new RatingDataException(
          field + " = " + value.toPlainString() + " has more than " + RATE_SCALE + " decimals");
    }
    return stored;
  }

  private static BigDecimal amount(String field, BigDecimal value, int integerDigits) {
    if (value.signum() < 0) {
      throw new RatingDataException(field + " must not be negative, was " + value.toPlainString());
    }
    BigDecimal stored;
    try {
      stored = CobolDecimal.store(field, value, integerDigits, AMOUNT_SCALE);
    } catch (RatingOverflowException e) {
      throw new RatingDataException(e.getMessage());
    }
    if (stored.compareTo(value) != 0) {
      throw new RatingDataException(field + " = " + value.toPlainString() + " is not whole cents");
    }
    return stored;
  }

  /**
   * PREMBAT writes SCHEDULE_MOD and DISCOUNT_PCT as literals and never applies them; applying them
   * would be a pricing change, so non-neutral values are rejected rather than ignored.
   */
  private static void requireNeutral(RatingParameters p) {
    if (p.scheduleMod().compareTo(BigDecimal.ONE) != 0
        || p.discountPct().compareTo(BigDecimal.ZERO) != 0) {
      throw new RatingDataException(
          "rate version "
              + p.rateVersion()
              + ": schedule mod "
              + p.scheduleMod().toPlainString()
              + " / discount "
              + p.discountPct().toPlainString()
              + " are not applied by PREMBAT; only neutral values are supported");
    }
  }
}
