package com.acme.insurance.pas.rating;

/**
 * Feature flag {@code pas.rating.factor-source}: which factors rating applies. The default keeps
 * parity with PREMBAT; switching is the epic's one intended premium change (quirk Q-03, FU-03).
 */
public enum RatingFactorMode {
  /** Every factor 1.0000, as PREMBAT. Default. */
  LEGACY_NEUTRAL,
  /** Territory and class factors from the reference tables. Needs Actuarial sign-off. */
  TABLE_DRIVEN;

  public RatingFactorSource source(FactorLookup territory, FactorLookup clazz) {
    return this == TABLE_DRIVEN
        ? RatingFactorSource.tableDriven(territory, clazz)
        : RatingFactorSource.LEGACY_NEUTRAL;
  }
}
