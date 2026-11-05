package com.acme.insurance.pas.rating;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the {@link RatingTable} in force on a rating date from the reference tables and keeps it
 * per date for at most {@code maxAge}, so online requests do not query per premium and a new
 * effective-dated rate row is picked up by every instance within {@code maxAge} without a restart.
 * A batch run that needs one consistent set of rates (PREMBAT 2000-LOAD-RATING-TABLES) takes a
 * {@link #snapshot()}, which never reloads.
 */
public final class ReferenceRatingTables {

  private record Loaded(RatingTable table, Instant loadedAt) {}

  private final RatingReferenceData data;
  private final Duration maxAge; // null: never reload
  private final Clock clock;
  private final Map<LocalDate, Loaded> byDate = new ConcurrentHashMap<>();

  public ReferenceRatingTables(RatingReferenceData data, Duration maxAge, Clock clock) {
    this(data, Objects.requireNonNull(maxAge, "maxAge"), clock, false);
    if (maxAge.isNegative()) {
      throw new IllegalArgumentException("maxAge must not be negative");
    }
  }

  private ReferenceRatingTables(
      RatingReferenceData data, Duration maxAge, Clock clock, boolean neverReload) {
    this.data = Objects.requireNonNull(data, "data");
    this.maxAge = neverReload ? null : maxAge;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Never reloads: for a single batch run or a test. */
  public static ReferenceRatingTables snapshotOf(RatingReferenceData data) {
    return new ReferenceRatingTables(data, null, Clock.systemUTC(), true);
  }

  /** A view over the same reference data that loads each date once and never reloads it. */
  public ReferenceRatingTables snapshot() {
    return snapshotOf(data);
  }

  public RatingTable tableFor(LocalDate ratingDate) {
    Objects.requireNonNull(ratingDate, "ratingDate");
    Instant now = clock.instant();
    return byDate
        .compute(
            ratingDate,
            (date, cached) ->
                cached != null && (maxAge == null || !now.isAfter(cached.loadedAt().plus(maxAge)))
                    ? cached
                    : new Loaded(
                        RatingTable.from(data.baseRatesInForce(date), data.parametersInForce(date)),
                        now))
        .table();
  }

  public void evict() {
    byDate.clear();
  }
}
