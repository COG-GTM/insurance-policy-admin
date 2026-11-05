package com.acme.insurance.pas.rating;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the {@link RatingTable} in force on a rating date from the reference tables and keeps it
 * per date, so a batch run reads the rates once (PREMBAT 2000-LOAD-RATING-TABLES) and online
 * requests do not query per premium. {@link #evict()} drops the cache after reference data changes.
 */
public final class ReferenceRatingTables {

  private final RatingReferenceData data;
  private final Map<LocalDate, RatingTable> byDate = new ConcurrentHashMap<>();

  public ReferenceRatingTables(RatingReferenceData data) {
    this.data = Objects.requireNonNull(data, "data");
  }

  public RatingTable tableFor(LocalDate ratingDate) {
    Objects.requireNonNull(ratingDate, "ratingDate");
    return byDate.computeIfAbsent(
        ratingDate,
        date -> RatingTable.from(data.baseRatesInForce(date), data.parametersInForce(date)));
  }

  public void evict() {
    byDate.clear();
  }
}
