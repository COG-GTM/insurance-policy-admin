package com.acme.insurance.pas.common.cobol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class CobolArithmetic {
  private CobolArithmetic() {}

  public static BigDecimal storeUnsigned(BigDecimal value, int integerDigits, int scale) {
    return store(value, integerDigits, scale, false);
  }

  public static BigDecimal storeSigned(BigDecimal value, int integerDigits, int scale) {
    return store(value, integerDigits, scale, true);
  }

  private static BigDecimal store(BigDecimal value, int integerDigits, int scale, boolean signed) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (integerDigits < 1 || scale < 0) {
      throw new IllegalArgumentException("integerDigits must be positive and scale nonnegative");
    }
    BigDecimal scaled = value.setScale(scale, RoundingMode.DOWN);
    boolean negative = signed && scaled.signum() < 0;
    BigInteger modulus = BigInteger.TEN.pow(integerDigits + scale);
    BigInteger stored = scaled.unscaledValue().abs().remainder(modulus);
    if (negative) {
      stored = stored.negate();
    }
    return new BigDecimal(stored, scale);
  }
}
