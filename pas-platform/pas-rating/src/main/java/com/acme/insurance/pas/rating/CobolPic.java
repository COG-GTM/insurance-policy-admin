package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class CobolPic {
  private CobolPic() {}

  static BigDecimal store(BigDecimal value, int integerDigits, int scale, String field) {
    BigDecimal stored = value.setScale(scale, RoundingMode.DOWN);
    BigDecimal capacity = BigDecimal.TEN.pow(integerDigits);
    if (stored.abs().compareTo(capacity) >= 0) {
      throw new PremiumOverflowException(field, picture(integerDigits, scale));
    }
    return stored;
  }

  private static String picture(int integerDigits, int scale) {
    return "S9(" + integerDigits + ")V9(" + scale + ")";
  }
}
