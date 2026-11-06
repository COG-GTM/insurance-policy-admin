package com.acme.insurance.pas.domain;

/**
 * PASAPP.RATING_PARAMETERS.PARAMETER_CODE: the scalar values PREMBAT 3200-CALCULATE-PREMIUM
 * hardcodes today (quirk Q-27). Precision and scale follow the COBOL receiving fields.
 */
public enum RatingParameterCode {
  /** Base premium for a policy type with no BASE_RATES row (PREMBAT {@code WHEN OTHER}). */
  OTHER_BASE_RATE(9, 2),
  /** Premium tax rate applied to the modified premium ({@code WS-TAX-RATE PIC S9(01)V9999}). */
  TAX_RATE(5, 4),
  /** Flat surcharge added to every premium ({@code WS-SURCHARGE PIC S9(07)V99}). */
  SURCHARGE_AMT(9, 2);

  private final int precision;
  private final int scale;

  RatingParameterCode(int precision, int scale) {
    this.precision = precision;
    this.scale = scale;
  }

  public int precision() {
    return precision;
  }

  public int scale() {
    return scale;
  }
}
