package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PASAPP.BASE_RATES row: the base premium for a POLICY_TYPE code from an effective date on. The
 * code is matched exactly as stored (CHAR(3)), the way PREMBAT's {@code EVALUATE POL-POLICY-TYPE}
 * does; any other code takes {@link RatingParameterCode#OTHER_BASE_RATE} (quirk Q-35).
 */
public record BaseRate(String policyType, LocalDate effectiveDate, BigDecimal baseRate) {

  public BaseRate {
    required(policyType, "policyType");
    if (policyType.length() != 3 || policyType.isBlank()) {
      throw new IllegalArgumentException("policyType must be a 3-character code");
    }
    required(effectiveDate, "effectiveDate");
    baseRate = RatingFactors.notNegative(decimal(required(baseRate, "baseRate"), 9, 2, "baseRate"));
  }
}
