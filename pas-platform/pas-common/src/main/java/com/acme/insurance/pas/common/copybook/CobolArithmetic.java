package com.acme.insurance.pas.common.copybook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Enterprise COBOL store semantics for arithmetic results moved into DISPLAY / COMP-3 numerics
 * without {@code ROUNDED} or {@code ON SIZE ERROR}: excess decimal places are truncated, excess
 * high-order integer digits are silently dropped, and an unsigned receiver keeps only the absolute
 * value. Java ports use these instead of {@code Money} (HALF_UP) wherever the COBOL truncates.
 */
public final class CobolArithmetic {

  private static final int MAX_DIGITS = 31;

  private CobolArithmetic() {}

  /** Value stored into {@code PIC S9(integerDigits)V9(decimalDigits)}. */
  public static BigDecimal storeSigned(BigDecimal value, int integerDigits, int decimalDigits) {
    Objects.requireNonNull(value, "value");
    checkDigits(integerDigits, decimalDigits);
    BigDecimal truncated = value.setScale(decimalDigits, RoundingMode.DOWN);
    BigDecimal modulus = BigDecimal.ONE.scaleByPowerOfTen(integerDigits);
    return truncated.remainder(modulus).setScale(decimalDigits, RoundingMode.UNNECESSARY);
  }

  /** Value stored into {@code PIC 9(integerDigits)V9(decimalDigits)} (sign dropped). */
  public static BigDecimal storeUnsigned(BigDecimal value, int integerDigits, int decimalDigits) {
    Objects.requireNonNull(value, "value");
    return storeSigned(value.abs(), integerDigits, decimalDigits);
  }

  /** Integer value stored into {@code PIC 9(integerDigits)} (sign dropped). */
  public static long storeUnsigned(long value, int integerDigits) {
    checkDigits(integerDigits, 0);
    if (integerDigits > 18) {
      throw new IllegalArgumentException("use the BigDecimal overload above 18 digits");
    }
    long modulus = 1;
    for (int i = 0; i < integerDigits; i++) {
      modulus *= 10;
    }
    long magnitude = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    return magnitude % modulus;
  }

  /** A whole number as a decimal, for comparisons against integer COBOL literals. */
  public static BigDecimal whole(long value) {
    return BigDecimal.valueOf(value);
  }

  private static void checkDigits(int integerDigits, int decimalDigits) {
    if (integerDigits < 1 || decimalDigits < 0 || integerDigits + decimalDigits > MAX_DIGITS) {
      throw new IllegalArgumentException(
          "unsupported PIC 9(" + integerDigits + ")V9(" + decimalDigits + ")");
    }
  }
}
