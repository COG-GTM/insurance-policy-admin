package com.acme.insurance.pas.domain;

import java.util.List;

/**
 * Every PASAPP.BASE_RATES and PASAPP.RATING_PARAMETERS row (all effective dates), read as one
 * consistent version of the rates.
 */
public record RatingRateHistory(List<BaseRate> baseRates, List<RatingParameter> parameters) {

  public RatingRateHistory {
    baseRates = List.copyOf(baseRates);
    parameters = List.copyOf(parameters);
  }
}
