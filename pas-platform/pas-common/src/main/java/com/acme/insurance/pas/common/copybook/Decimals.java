package com.acme.insurance.pas.common.copybook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

final class Decimals {

  private Decimals() {}

  static BigDecimal rescaleExact(BigDecimal value, int scale) {
    Objects.requireNonNull(value, "value");
    try {
      return value.setScale(scale, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new CopybookDataException(
          "value " + value.toPlainString() + " has more than " + scale + " decimal places", e);
    }
  }
}
