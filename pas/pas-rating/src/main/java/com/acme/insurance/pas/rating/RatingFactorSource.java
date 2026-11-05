package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.ClassFactor;
import com.acme.insurance.pas.domain.RatingRateHistory;
import com.acme.insurance.pas.domain.TerritoryFactor;
import java.math.BigDecimal;
import java.util.Objects;

/** Chooses the factors a request is rated with. Selected by {@link RatingFactorMode}. */
@FunctionalInterface
public interface RatingFactorSource {

  RatingFactors factorsFor(RatingRequest request);

  /**
   * This source over the factor rows of {@code rows} only, for one batch run (see {@link
   * PremiumRater#snapshot()}). Sources that read no reference table return themselves.
   */
  default RatingFactorSource pin(RatingRateHistory rows) {
    return this;
  }

  /**
   * Whether {@link #pin} needs the territory and class factor rows. Sources that ignore the factor
   * tables do not read them, so a factor row they would never use cannot stop a batch run.
   */
  default boolean readsFactorTables() {
    return false;
  }

  /** PREMBAT today: every factor 1.0000, whatever the reference tables hold (quirk Q-03). */
  RatingFactorSource LEGACY_NEUTRAL = request -> RatingFactors.NEUTRAL;

  /**
   * Territory and class factors from the effective-dated reference tables (FU-03). A missing code
   * or a code with no row in force keeps the neutral factor; experience and schedule mods stay
   * neutral until their source tables exist. This changes premiums once factor rows are loaded, so
   * it is only enabled behind {@link RatingFactorMode#TABLE_DRIVEN} after Actuarial sign-off. Its
   * {@link #pin} resolves factors from the copied rows instead of the live tables.
   */
  static RatingFactorSource tableDriven(FactorLookup territory, FactorLookup clazz) {
    Objects.requireNonNull(territory, "territory");
    Objects.requireNonNull(clazz, "clazz");
    return new RatingFactorSource() {
      @Override
      public RatingFactors factorsFor(RatingRequest request) {
        return RatingFactors.territoryAndClass(
            lookup(
                territory,
                request.territoryCode(),
                request,
                RatingFactors.NEUTRAL.territoryFactor()),
            lookup(clazz, request.classCode(), request, RatingFactors.NEUTRAL.classFactor()));
      }

      @Override
      public boolean readsFactorTables() {
        return true;
      }

      @Override
      public RatingFactorSource pin(RatingRateHistory rows) {
        Objects.requireNonNull(rows, "rows");
        return tableDriven(
            FactorLookup.of(
                rows.territoryFactors(),
                TerritoryFactor::territoryCode,
                TerritoryFactor::effectiveDate,
                TerritoryFactor::ratingFactor),
            FactorLookup.of(
                rows.classFactors(),
                ClassFactor::classCode,
                ClassFactor::effectiveDate,
                ClassFactor::ratingFactor));
      }
    };
  }

  private static BigDecimal lookup(
      FactorLookup lookup, String code, RatingRequest request, BigDecimal neutral) {
    if (code == null || code.isBlank()) {
      return neutral;
    }
    return lookup.factor(code, request.ratingDate()).orElse(neutral);
  }
}
