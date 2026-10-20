package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.common.copybook.CobolArithmetic;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * POLRNW premium and term arithmetic ({@code 4000-CALCULATE-NEW-PREMIUM}, {@code
 * 5000-APPLY-RATE-CAP}, {@code 6000-CREATE-RENEWAL-TERM}) with the COBOL store semantics the parity
 * goldens pin:
 *
 * <ul>
 *   <li>Q-05 / Q-06: new premium = old x (1 + uplift), truncated into {@code PIC S9(9)V99}; the
 *       rate change is truncated into {@code PIC S9(3)V99}.
 *   <li>Q-04: the cap replaces the premium only when the rate change exceeds it (unreachable while
 *       the uplift is below the cap).
 *   <li>Q-08: a zero premium gives a 0.00 rate change instead of a divide error.
 *   <li>Q-07: the renewal count wraps in {@code PIC 9(3)} and the new expiry is stored in {@code
 *       PIC 9(8)} (99991231 renews to 1231).
 *   <li>Q-01 (Fix): the new expiry is the old expiry plus one calendar year, so a Feb 29 expiry
 *       renews to Feb 28 instead of the invalid YYYYMMDD + 10000.
 * </ul>
 */
public final class RenewalCalculator {

  static final int PREMIUM_INTEGER_DIGITS = 9;
  static final int MONEY_DECIMALS = 2;
  static final int PCT_INTEGER_DIGITS = 3;
  static final int RENEWAL_COUNT_DIGITS = 3;
  static final int DATE_DIGITS = 8;
  private static final int PERCENT_SHIFT = 2;
  private static final int DIVISION_SCALE = 12;
  private static final BigDecimal HUNDRED = CobolArithmetic.whole(100);

  /** Outcome of the arithmetic, before anything is written. */
  public record RenewalTerms(
      BigDecimal newPremium,
      BigDecimal rateChangePct,
      boolean capApplied,
      int newRenewalCount,
      int newEffectiveDate,
      int newExpiryDate) {}

  private RenewalCalculator() {}

  /**
   * @param oldPremium current {@code TOTAL_PREMIUM}
   * @param upliftPct standard renewal uplift in percent (POLRNW literal 1.05 = 5)
   * @param rateCapPct rate-increase cap in percent (POLRNW {@code WS-RATE-INCREASE-CAP})
   * @param expiryDate current expiry as YYYYMMDD
   * @param renewalCount current {@code RENEWAL_COUNT}
   */
  public static RenewalTerms calculate(
      BigDecimal oldPremium,
      BigDecimal upliftPct,
      BigDecimal rateCapPct,
      int expiryDate,
      int renewalCount) {
    Objects.requireNonNull(oldPremium, "oldPremium");
    BigDecimal old = oldPremium.setScale(MONEY_DECIMALS, RoundingMode.DOWN);
    BigDecimal newPremium = premiumTimes(old, upliftPct);
    BigDecimal ratePct =
        old.signum() == 0
            ? BigDecimal.ZERO.setScale(MONEY_DECIMALS)
            : CobolArithmetic.storeSigned(
                newPremium
                    .subtract(old)
                    .divide(old, DIVISION_SCALE, RoundingMode.DOWN)
                    .multiply(HUNDRED),
                PCT_INTEGER_DIGITS,
                MONEY_DECIMALS);
    boolean capApplied = ratePct.compareTo(rateCapPct) > 0;
    if (capApplied) {
      newPremium = premiumTimes(old, rateCapPct);
    }
    int count = (int) CobolArithmetic.storeUnsigned(renewalCount + 1L, RENEWAL_COUNT_DIGITS);
    return new RenewalTerms(
        newPremium, ratePct, capApplied, count, expiryDate, oneYearLater(expiryDate));
  }

  /** Q-01 fix: calendar year arithmetic, stored into {@code PIC 9(8)} (Q-07). */
  static int oneYearLater(int yyyymmdd) {
    LocalDate next = YyyyMmDd.toDate(yyyymmdd).plusYears(1);
    long encoded = next.getYear() * 10_000L + next.getMonthValue() * 100L + next.getDayOfMonth();
    return (int) CobolArithmetic.storeUnsigned(encoded, DATE_DIGITS);
  }

  private static BigDecimal premiumTimes(BigDecimal old, BigDecimal pct) {
    BigDecimal factor = BigDecimal.ONE.add(pct.movePointLeft(PERCENT_SHIFT));
    return CobolArithmetic.storeSigned(
        old.multiply(factor), PREMIUM_INTEGER_DIGITS, MONEY_DECIMALS);
  }
}
