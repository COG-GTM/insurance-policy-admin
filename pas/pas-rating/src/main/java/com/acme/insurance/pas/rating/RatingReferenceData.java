package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import com.acme.insurance.pas.domain.RatingRateHistory;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Rate rows in force on a rating date, one per key. Implemented over RatingRateJdbcRepository by
 * the applications.
 */
public interface RatingReferenceData {

  List<BaseRate> baseRatesInForce(LocalDate ratingDate);

  List<RatingParameter> parametersInForce(LocalDate ratingDate);

  /**
   * An immutable copy of every row (all effective dates), so a batch run resolves every rating date
   * against the same version of the rates even if a row is inserted while it runs.
   */
  RatingReferenceData pin();

  /**
   * Live data: per-date lookups, and the full row history for {@link #pin()}, which must be read as
   * one consistent version of both tables.
   */
  static RatingReferenceData of(
      Function<LocalDate, List<BaseRate>> baseRates,
      Function<LocalDate, List<RatingParameter>> parameters,
      Supplier<RatingRateHistory> history) {
    Objects.requireNonNull(baseRates, "baseRates");
    Objects.requireNonNull(parameters, "parameters");
    Objects.requireNonNull(history, "history");
    return new RatingReferenceData() {
      @Override
      public List<BaseRate> baseRatesInForce(LocalDate ratingDate) {
        return baseRates.apply(ratingDate);
      }

      @Override
      public List<RatingParameter> parametersInForce(LocalDate ratingDate) {
        return parameters.apply(ratingDate);
      }

      @Override
      public RatingReferenceData pin() {
        RatingRateHistory rows = history.get();
        return history(rows.baseRates(), rows.parameters());
      }
    };
  }

  /**
   * In-memory rows of all effective dates; each lookup takes the latest row on or before the date.
   */
  static RatingReferenceData history(
      Collection<BaseRate> baseRates, Collection<RatingParameter> parameters) {
    List<BaseRate> rates = List.copyOf(baseRates);
    List<RatingParameter> params = List.copyOf(parameters);
    return new RatingReferenceData() {
      @Override
      public List<BaseRate> baseRatesInForce(LocalDate ratingDate) {
        return latest(rates, BaseRate::policyType, BaseRate::effectiveDate, ratingDate);
      }

      @Override
      public List<RatingParameter> parametersInForce(LocalDate ratingDate) {
        return latest(params, RatingParameter::code, RatingParameter::effectiveDate, ratingDate);
      }

      @Override
      public RatingReferenceData pin() {
        return this;
      }
    };
  }

  private static <T, K> List<T> latest(
      List<T> rows, Function<T, K> key, Function<T, LocalDate> effective, LocalDate ratingDate) {
    Map<K, T> inForce =
        rows.stream()
            .filter(row -> !effective.apply(row).isAfter(ratingDate))
            .collect(
                Collectors.toMap(
                    key,
                    row -> row,
                    (a, b) -> Comparator.comparing(effective).compare(a, b) >= 0 ? a : b));
    return List.copyOf(inForce.values());
  }
}
