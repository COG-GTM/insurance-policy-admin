package com.acme.insurance.pas.domain;

import java.util.List;

/**
 * Every PASAPP.BASE_RATES, PASAPP.RATING_PARAMETERS, ACMEINS.TERRITORY_FACTORS and
 * PASAPP.CLASS_FACTORS row (all effective dates), read as one consistent version of the rating
 * reference data.
 */
public record RatingRateHistory(
    List<BaseRate> baseRates,
    List<RatingParameter> parameters,
    List<TerritoryFactor> territoryFactors,
    List<ClassFactor> classFactors) {

  public RatingRateHistory {
    baseRates = List.copyOf(baseRates);
    parameters = List.copyOf(parameters);
    territoryFactors = List.copyOf(territoryFactors);
    classFactors = List.copyOf(classFactors);
  }

  /** Rates only, no factor rows. */
  public RatingRateHistory(List<BaseRate> baseRates, List<RatingParameter> parameters) {
    this(baseRates, parameters, List.of(), List.of());
  }
}
