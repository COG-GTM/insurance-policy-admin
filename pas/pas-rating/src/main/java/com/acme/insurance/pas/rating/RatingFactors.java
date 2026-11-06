package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Rating factors applied to the base premium (PREMIUMS columns TERRITORY_FACTOR, CLASS_FACTOR,
 * EXPERIENCE_MOD, SCHEDULE_MOD, DISCOUNT_PCT). Schedule mod and discount are recorded but not
 * applied, as in PREMBAT. Factors are positive with at most 4 decimal places ({@code S9(03)V9999});
 * the discount has at most 2.
 */
public record RatingFactors(
    BigDecimal territoryFactor,
    BigDecimal classFactor,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal discountPct) {

  private static final BigDecimal NEUTRAL_FACTOR =
      com.acme.insurance.pas.domain.RatingFactors.NEUTRAL;

  /** What PREMBAT uses today: the factor tables are never loaded (quirk Q-03). */
  public static final RatingFactors NEUTRAL =
      new RatingFactors(
          NEUTRAL_FACTOR,
          NEUTRAL_FACTOR,
          NEUTRAL_FACTOR,
          NEUTRAL_FACTOR,
          BigDecimal.ZERO.setScale(2));

  public RatingFactors {
    territoryFactor = factor(territoryFactor, "territoryFactor");
    classFactor = factor(classFactor, "classFactor");
    experienceMod = factor(experienceMod, "experienceMod");
    scheduleMod = factor(scheduleMod, "scheduleMod");
    Objects.requireNonNull(discountPct, "discountPct");
    if (discountPct.scale() > 2) {
      throw new IllegalArgumentException("discountPct has more than 2 decimal places");
    }
  }

  /** Neutral factors except territory and class, as table-driven rating sets them (FU-03). */
  public static RatingFactors territoryAndClass(
      BigDecimal territoryFactor, BigDecimal classFactor) {
    return new RatingFactors(
        territoryFactor,
        classFactor,
        NEUTRAL.experienceMod(),
        NEUTRAL.scheduleMod(),
        NEUTRAL.discountPct());
  }

  private static BigDecimal factor(BigDecimal factor, String name) {
    Objects.requireNonNull(factor, name);
    if (factor.signum() <= 0 || factor.scale() > 4 || factor.precision() - factor.scale() > 3) {
      throw new IllegalArgumentException(name + " must be a positive S9(03)V9999 value: " + factor);
    }
    return factor;
  }
}
