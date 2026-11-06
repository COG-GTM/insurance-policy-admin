package com.acme.insurance.pas.domain;

import java.math.BigDecimal;

/** Rules shared by the rating reference tables. */
public final class RatingFactors {

  /** Factor applied when no reference row exists, matching PREMBAT's hardcoded 1.0000 (Q-03). */
  public static final BigDecimal NEUTRAL = new BigDecimal("1.0000");

  private RatingFactors() {}

  static BigDecimal positive(BigDecimal factor) {
    if (factor.signum() <= 0) {
      throw new IllegalArgumentException("ratingFactor must be greater than zero: " + factor);
    }
    return factor;
  }

  static BigDecimal notNegative(BigDecimal amount) {
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("rate must not be negative: " + amount);
    }
    return amount;
  }
}
