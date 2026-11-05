package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Rating factors applied to the base premium (PREMIUMS columns TERRITORY_FACTOR, CLASS_FACTOR,
 * EXPERIENCE_MOD, SCHEDULE_MOD, DISCOUNT_PCT). Schedule mod and discount are recorded but not
 * applied, as in PREMBAT.
 */
public record RatingFactors(
    BigDecimal territoryFactor,
    BigDecimal classFactor,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal discountPct) {

  /** What PREMBAT uses today: the factor tables are never loaded (quirk Q-03). */
  public static final RatingFactors NEUTRAL =
      new RatingFactors(
          new BigDecimal("1.0000"),
          new BigDecimal("1.0000"),
          new BigDecimal("1.0000"),
          new BigDecimal("1.0000"),
          new BigDecimal("0.00"));

  public RatingFactors {
    Objects.requireNonNull(territoryFactor, "territoryFactor");
    Objects.requireNonNull(classFactor, "classFactor");
    Objects.requireNonNull(experienceMod, "experienceMod");
    Objects.requireNonNull(scheduleMod, "scheduleMod");
    Objects.requireNonNull(discountPct, "discountPct");
  }
}
