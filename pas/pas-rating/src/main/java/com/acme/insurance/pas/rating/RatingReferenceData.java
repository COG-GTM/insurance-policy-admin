package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Rate rows in force on a rating date, one per key. Implemented over RatingRateJdbcRepository by
 * the applications.
 */
public interface RatingReferenceData {

  List<BaseRate> baseRatesInForce(LocalDate ratingDate);

  List<RatingParameter> parametersInForce(LocalDate ratingDate);

  static RatingReferenceData of(
      Function<LocalDate, List<BaseRate>> baseRates,
      Function<LocalDate, List<RatingParameter>> parameters) {
    Objects.requireNonNull(baseRates, "baseRates");
    Objects.requireNonNull(parameters, "parameters");
    return new RatingReferenceData() {
      @Override
      public List<BaseRate> baseRatesInForce(LocalDate ratingDate) {
        return baseRates.apply(ratingDate);
      }

      @Override
      public List<RatingParameter> parametersInForce(LocalDate ratingDate) {
        return parameters.apply(ratingDate);
      }
    };
  }
}
