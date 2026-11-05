package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.util.Objects;

/**
 * POLRNW renewal pricing and term dates. COBOL truncates every result to the cent (Q-06), so this
 * uses {@link RoundingMode#DOWN}. Fixes against the legacy program, each a known difference pending
 * sign-off: the expiry is a real calendar date instead of {@code YYYYMMDD + 10000} (Q-01), and a
 * zero premium renews at zero instead of dividing by zero (Q-41).
 */
public final class RenewalCalculator {

  /** ACMEINS.POLICIES.TOTAL_PREMIUM is DECIMAL(11,2): 9 integer digits. */
  static final int PREMIUM_INTEGER_DIGITS = 9;

  /** ACMEINS.POLICIES.RENEWAL_COUNT is SMALLINT. */
  static final int MAX_RENEWAL_COUNT = Short.MAX_VALUE;

  private final RenewalRules rules;

  public RenewalCalculator(RenewalRules rules) {
    this.rules = Objects.requireNonNull(rules, "rules");
  }

  public RenewalRules rules() {
    return rules;
  }

  /**
   * Prices the next term of a policy.
   *
   * @param currentPremium the expiring TOTAL_PREMIUM; {@code null} is the column default 0
   * @param currentExpiry the expiring EXPIRY_DATE, which becomes the new effective date
   * @param renewalCount the current RENEWAL_COUNT; {@code null} is the column default 0
   * @throws RatingOverflowException if the premium or the renewal count no longer fits its column
   */
  public RenewalQuote quote(
      BigDecimal currentPremium, LocalDate currentExpiry, Integer renewalCount) {
    Objects.requireNonNull(currentExpiry, "currentExpiry");
    BigDecimal old =
        (currentPremium == null ? BigDecimal.ZERO : currentPremium).setScale(2, RoundingMode.DOWN);
    BigDecimal newPremium = cents(old.multiply(factor(rules.upliftPct())));
    BigDecimal rateChangePct =
        old.signum() == 0
            ? BigDecimal.ZERO.setScale(2)
            : newPremium.subtract(old).movePointRight(2).divide(old, 2, RoundingMode.DOWN);
    BigDecimal capPremium = cents(old.multiply(factor(rules.rateIncreaseCapPct())));
    boolean capped = newPremium.compareTo(capPremium) > 0;
    if (capped) {
      newPremium = capPremium;
    }
    if (overflows(newPremium)) {
      throw new RatingOverflowException(
          "renewal premium " + newPremium + " does not fit TOTAL_PREMIUM DECIMAL(11,2)");
    }
    int count = renewalCount == null ? 0 : renewalCount;
    if (count >= MAX_RENEWAL_COUNT) {
      throw new RatingOverflowException("RENEWAL_COUNT " + count + " cannot be incremented");
    }
    return new RenewalQuote(
        old,
        newPremium,
        rateChangePct,
        capped,
        currentExpiry,
        renewalExpiry(currentExpiry),
        count + 1);
  }

  /**
   * One year after {@code expiry}; a 29 February expiry follows {@link RenewalRules#leapDayExpiry}.
   */
  public LocalDate renewalExpiry(LocalDate expiry) {
    LocalDate next = expiry.plusYears(1);
    boolean leapDay = expiry.getMonth() == Month.FEBRUARY && expiry.getDayOfMonth() == 29;
    if (leapDay && rules.leapDayExpiry() == RenewalRules.LeapDayExpiry.MAR_01) {
      return next.plusDays(1);
    }
    return next;
  }

  private static BigDecimal factor(BigDecimal pct) {
    return BigDecimal.ONE.add(pct.movePointLeft(2));
  }

  /** Whether a money amount no longer fits DECIMAL(11,2). */
  static boolean overflows(BigDecimal amount) {
    return amount.precision() - amount.scale() > PREMIUM_INTEGER_DIGITS;
  }

  private static BigDecimal cents(BigDecimal value) {
    return value.setScale(2, RoundingMode.DOWN);
  }
}
