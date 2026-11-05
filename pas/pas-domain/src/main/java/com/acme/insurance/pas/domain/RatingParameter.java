package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;

import java.math.BigDecimal;
import java.time.LocalDate;

/** PASAPP.RATING_PARAMETERS row: one scalar rating value from an effective date on. */
public record RatingParameter(RatingParameterCode code, LocalDate effectiveDate, BigDecimal value) {

  public RatingParameter {
    required(code, "code");
    required(effectiveDate, "effectiveDate");
    value =
        RatingFactors.notNegative(
            decimal(required(value, "value"), code.precision(), code.scale(), code.name()));
  }
}
