package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.PolicyTerm;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * POLEND 3000-CALCULATE-PRORATA: the share of the term still to run on the endorsement date and the
 * premium adjustment it gives. POLEND computes the factor but never applies it (Q-20); here the
 * adjustment is {@code annualPremiumChange × factor}, truncated to the cent, and an endorsement
 * without a premium change adjusts by 0.00 exactly like POLEND. Day counts are not truncated to
 * three digits, the remaining days are never negative after expiry or larger than the term, and a
 * zero-length term keeps POLEND's factor of 1.
 *
 * @param daysInTerm days from EFFECTIVE_DATE to EXPIRY_DATE
 * @param daysRemaining days from the endorsement date to EXPIRY_DATE, within 0..daysInTerm
 * @param factor daysRemaining / daysInTerm with 6 decimals, truncated (WS-PRORATA-FACTOR)
 * @param adjustment the PREMIUM_ADJUSTMENT written to ENDORSEMENTS and added to TOTAL_PREMIUM
 */
public record ProRata(
    long daysInTerm, long daysRemaining, BigDecimal factor, BigDecimal adjustment) {

  /** WS-PRORATA-FACTOR is PIC 9V9(6). */
  public static final int FACTOR_SCALE = 6;

  /**
   * @throws IllegalArgumentException if the policy expires before it takes effect
   * @throws RatingOverflowException if the adjustment does not fit DECIMAL(11,2)
   */
  public static ProRata of(
      LocalDate effectiveDate,
      LocalDate expiryDate,
      LocalDate endorsementDate,
      BigDecimal annualPremiumChange) {
    Objects.requireNonNull(endorsementDate, "endorsementDate");
    PolicyTerm term = new PolicyTerm(effectiveDate, expiryDate);
    long remaining = term.daysRemaining(endorsementDate);
    BigDecimal factor = term.remainingShare(endorsementDate, FACTOR_SCALE);
    BigDecimal change = annualPremiumChange == null ? BigDecimal.ZERO : annualPremiumChange;
    BigDecimal adjustment = change.multiply(factor).setScale(2, RoundingMode.DOWN);
    if (RenewalCalculator.overflows(adjustment)) {
      throw new RatingOverflowException(
          "premium adjustment " + adjustment + " does not fit DECIMAL(11,2)");
    }
    return new ProRata(term.days(), remaining, factor, adjustment);
  }

  /**
   * {@code premium + adjustment}, checked against TOTAL_PREMIUM DECIMAL(11,2).
   *
   * @throws RatingOverflowException if the new premium does not fit
   */
  public BigDecimal applyTo(BigDecimal premium) {
    BigDecimal result =
        (premium == null ? BigDecimal.ZERO : premium)
            .add(adjustment)
            .setScale(2, RoundingMode.DOWN);
    if (RenewalCalculator.overflows(result)) {
      throw new RatingOverflowException(
          "endorsed premium " + result + " does not fit TOTAL_PREMIUM DECIMAL(11,2)");
    }
    return result;
  }
}
