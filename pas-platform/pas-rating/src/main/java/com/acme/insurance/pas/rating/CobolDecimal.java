package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * COBOL {@code COMPUTE}/{@code MOVE} into a signed numeric PIC {@code S9(i)V9(s)}: without ROUNDED
 * the low-order digits are truncated toward zero; a result that needs more than {@code i} integer
 * digits is rejected with {@link RatingOverflowException}.
 */
final class CobolDecimal {

  private CobolDecimal() {}

  static BigDecimal store(String field, BigDecimal value, int integerDigits, int scale) {
    BigDecimal stored = value.setScale(scale, RoundingMode.DOWN);
    if (stored.precision() - stored.scale() > integerDigits) {
      throw new RatingOverflowException(
          field
              + " = "
              + stored.toPlainString()
              + " does not fit PIC S9("
              + integerDigits
              + ")V9("
              + scale
              + ")");
    }
    return stored;
  }
}
