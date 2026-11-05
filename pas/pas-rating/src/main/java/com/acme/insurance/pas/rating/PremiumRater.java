package com.acme.insurance.pas.rating;

import java.util.Objects;

/**
 * Entry point shared by the premium batch job and the online flows: rates a request with the
 * reference rates in force on its rating date and the factors chosen by the configured {@link
 * RatingFactorSource}.
 */
public final class PremiumRater {

  private final ReferenceRatingTables tables;
  private final RatingFactorSource factors;

  public PremiumRater(ReferenceRatingTables tables, RatingFactorSource factors) {
    this.tables = Objects.requireNonNull(tables, "tables");
    this.factors = Objects.requireNonNull(factors, "factors");
  }

  public PremiumBreakdown rate(RatingRequest request) {
    Objects.requireNonNull(request, "request");
    return new PremiumCalculator(tables.tableFor(request.ratingDate()))
        .calculate(request.policyType(), factors.factorsFor(request));
  }
}
