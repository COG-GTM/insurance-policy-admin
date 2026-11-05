package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import java.math.BigDecimal;
import java.time.LocalDate;

/** ACMEINS.TERRITORY_FACTORS row: a rating factor for a territory from an effective date on. */
public record TerritoryFactor(
    String territoryCode, LocalDate effectiveDate, BigDecimal ratingFactor) {

  public TerritoryFactor {
    text(required(territoryCode, "territoryCode"), 6, "territoryCode");
    required(effectiveDate, "effectiveDate");
    ratingFactor =
        RatingFactors.positive(
            decimal(required(ratingFactor, "ratingFactor"), 7, 4, "ratingFactor"));
  }
}
