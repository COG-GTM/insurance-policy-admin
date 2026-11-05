package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.RatingRateHistory;
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
 * {@link #snapshot()}: all rate rows copied once at the start of the run.
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

  /**
   * For one batch run: copies every rate row now ({@link RatingReferenceData#pin()}) and resolves
   * each rating date from that copy, so rows inserted during the run never mix into it.
   */
  public ReferenceRatingTables snapshot() {
    return snapshotOf(data.pin());
  }

  /**
   * Every rate row, plus the factor rows when {@code withFactors}, read as one consistent version
   * (see {@link #snapshot()}).
   */
  public RatingRateHistory allRows(boolean withFactors) {
    return data.allRows(withFactors);
  }

  /**
   * The table in force on {@code ratingDate}. A load also drops every entry older than {@code
   * maxAge}, so the cache holds only dates rated within the last {@code maxAge}. A snapshot keeps
   * its dates for the life of the run, from rows that are already in memory.
   */
  public RatingTable tableFor(LocalDate ratingDate) {
    Objects.requireNonNull(ratingDate, "ratingDate");
    Instant now = clock.instant();
    Loaded cached = byDate.get(ratingDate);
    if (cached != null && fresh(cached, now)) {
      return cached.table();
    }
    Loaded loaded =
        byDate.compute(
            ratingDate,
            (date, current) ->
                current != null && fresh(current, now)
                    ? current
                    : new Loaded(
                        RatingTable.from(data.baseRatesInForce(date), data.parametersInForce(date)),
                        now));
    if (maxAge != null) {
      byDate.values().removeIf(entry -> !fresh(entry, now));
    }
    return loaded.table();
  }

  int cachedDates() {
    return byDate.size();
  }

  private boolean fresh(Loaded entry, Instant now) {
    return maxAge == null || !now.isAfter(entry.loadedAt().plus(maxAge));
  }

  public void evict() {
    byDate.clear();
  }
}
