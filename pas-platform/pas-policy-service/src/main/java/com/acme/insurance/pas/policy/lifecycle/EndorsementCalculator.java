package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.common.copybook.CobolArithmetic;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * POLEND {@code 4000-CALCULATE-PRORATA} and the premium arithmetic of {@code
 * 5000-APPLY-ENDORSEMENT}, at parity:
 *
 * <ul>
 *   <li>Q-12: days in term and days remaining are stored in unsigned {@code PIC 9(3)} (a 1096-day
 *       term is 96 days; 14 days past expiry is +14).
 *   <li>Q-13: {@code INTEGER-OF-DATE} of an invalid date is 0.
 *   <li>A zero-day term gives factor 1; otherwise remaining / term truncated into {@code PIC
 *       S9(1)V9(6)}.
 *   <li>Q-11: the premium adjustment is never computed, so it is 0.00 and the premium is unchanged.
 * </ul>
 */
public final class EndorsementCalculator {

  static final int DAY_DIGITS = 3;
  static final int FACTOR_INTEGER_DIGITS = 1;
  static final int FACTOR_DECIMALS = 6;
  static final int PREMIUM_INTEGER_DIGITS = 9;
  static final int MONEY_DECIMALS = 2;
  static final int SEQ_DIGITS = 5;
  private static final int DIVISION_SCALE = 12;

  /** Pro-rata outcome. */
  public record ProRata(int daysInTerm, int daysRemaining, BigDecimal factor) {}

  private EndorsementCalculator() {}

  /** Dates as YYYYMMDD; {@code asOf} is the processing date (CICS FORMATTIME). */
  public static ProRata proRata(int effectiveDate, int expiryDate, int asOf) {
    long expiry = YyyyMmDd.integerOfDate(expiryDate);
    int term =
        (int)
            CobolArithmetic.storeUnsigned(
                expiry - YyyyMmDd.integerOfDate(effectiveDate), DAY_DIGITS);
    int remaining =
        (int) CobolArithmetic.storeUnsigned(expiry - YyyyMmDd.integerOfDate(asOf), DAY_DIGITS);
    BigDecimal factor =
        term > 0
            ? CobolArithmetic.storeSigned(
                CobolArithmetic.whole(remaining)
                    .divide(CobolArithmetic.whole(term), DIVISION_SCALE, RoundingMode.DOWN),
                FACTOR_INTEGER_DIGITS,
                FACTOR_DECIMALS)
            : BigDecimal.ONE.setScale(FACTOR_DECIMALS);
    return new ProRata(term, remaining, factor);
  }

  /**
   * Q-11 (Preserve): POLEND never assigns {@code WS-PREMIUM-ADJUST}, so every endorsement adjusts
   * the premium by 0.00. Pricing endorsements is a product change (open question OQ-06).
   */
  public static BigDecimal premiumAdjustment(ProRata proRata) {
    return BigDecimal.ZERO.setScale(MONEY_DECIMALS);
  }

  /** {@code POLICY-TOTAL-PREMIUM + WS-PREMIUM-ADJUST} into {@code PIC S9(9)V99}. */
  public static BigDecimal adjustedPremium(BigDecimal totalPremium, BigDecimal adjustment) {
    BigDecimal base = totalPremium == null ? BigDecimal.ZERO : totalPremium;
    return CobolArithmetic.storeSigned(
        base.add(adjustment), PREMIUM_INTEGER_DIGITS, MONEY_DECIMALS);
  }

  /** {@code COALESCE(MAX(ENDORSEMENT_SEQ), 0) + 1} into {@code PIC 9(5)}. */
  public static int nextSequence(int maxSequence) {
    return (int) CobolArithmetic.storeUnsigned(maxSequence + 1L, SEQ_DIGITS);
  }
}
