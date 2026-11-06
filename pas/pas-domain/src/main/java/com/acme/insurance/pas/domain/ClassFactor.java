package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PASAPP.CLASS_FACTORS row: a rating factor for a COVERAGES.CLASS_CODE from an effective date on.
 * Same shape as {@link TerritoryFactor}; the legacy system hardcodes 1.00 (quirk Q-03).
 */
public record ClassFactor(String classCode, LocalDate effectiveDate, BigDecimal ratingFactor) {

  public ClassFactor {
    text(required(classCode, "classCode"), 5, "classCode");
    required(effectiveDate, "effectiveDate");
    ratingFactor =
        RatingFactors.positive(
            decimal(required(ratingFactor, "ratingFactor"), 7, 4, "ratingFactor"));
  }
}
