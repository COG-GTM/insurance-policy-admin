package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.RatingRateHistory;
import java.util.Objects;

/**
 * Entry point shared by the premium batch job and the online flows: rates a request with the
 * reference rates in force on its rating date and the factors chosen by the configured {@link
 * RatingFactorSource}. The application bean reads live (rates cached for {@code
 * pas.rating.cache-ttl}); a batch run must rate through {@link #snapshot()}.
 */
public final class PremiumRater {

  private final ReferenceRatingTables tables;
  private final RatingFactorSource factors;

  public PremiumRater(ReferenceRatingTables tables, RatingFactorSource factors) {
    this.tables = Objects.requireNonNull(tables, "tables");
    this.factors = Objects.requireNonNull(factors, "factors");
  }

  /**
   * A rater for one batch run (PREMBAT 2000-LOAD-RATING-TABLES): copies every rate row, and every
   * factor row if the factor source reads them, now, in one consistent read, and rates every
   * request of the run from that copy, so rows inserted while the run is in progress never mix into
   * it.
   */
  public PremiumRater snapshot() {
    RatingRateHistory rows = tables.allRows(factors.readsFactorTables());
    return new PremiumRater(
        ReferenceRatingTables.snapshotOf(RatingReferenceData.history(rows)), factors.pin(rows));
  }

  public PremiumBreakdown rate(RatingRequest request) {
    Objects.requireNonNull(request, "request");
    return new PremiumCalculator(tables.tableFor(request.ratingDate()))
        .calculate(request.policyType(), factors.factorsFor(request));
  }
}
