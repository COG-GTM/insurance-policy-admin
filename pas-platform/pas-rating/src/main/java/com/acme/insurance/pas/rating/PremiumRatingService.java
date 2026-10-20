package com.acme.insurance.pas.rating;

import java.time.LocalDate;

/**
 * Entry point for the batch job and the online flows: loads the tables for the configured factor
 * set and rates requests with {@link PremiumCalculator}. Batch callers should load one {@link
 * #snapshot} per run (as PREMBAT does) and call {@link #rate(RatingSnapshot, RatingRequest)}.
 */
public class PremiumRatingService {

  private final RatingTables tables;
  private final PremiumCalculator calculator;
  private final String factorSet;

  public PremiumRatingService(
      RatingTables tables, PremiumCalculator calculator, RatingProperties properties) {
    this.tables = tables;
    this.calculator = calculator;
    this.factorSet = properties.factorSet();
  }

  public String factorSet() {
    return factorSet;
  }

  public RatingSnapshot snapshot(LocalDate ratingDate) {
    return tables.load(ratingDate, factorSet);
  }

  public PremiumBreakdown rate(RatingSnapshot snapshot, RatingRequest request) {
    return calculator.rate(snapshot, request);
  }

  public PremiumBreakdown rate(RatingRequest request, LocalDate ratingDate) {
    return calculator.rate(snapshot(ratingDate), request);
  }
}
