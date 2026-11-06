package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Column-width checks shared by the domain records, matching the DB2 DDL and the copybooks. */
public final class Constraints {

  private Constraints() {}

  /**
   * Returns {@code value} with exactly {@code scale} decimal places, or fails if that would lose
   * digits or the value needs more than {@code precision} digits (DB2 {@code DECIMAL(p,s)}, COBOL
   * {@code S9(p-s)V9(s)}). Rounding is never applied here; rating rounds explicitly (quirk Q-06).
   */
  public static BigDecimal decimal(BigDecimal value, int precision, int scale, String field) {
    if (value == null) {
      return null;
    }
    BigDecimal scaled;
    try {
      scaled = value.setScale(scale, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(field + " has more than " + scale + " decimal places", e);
    }
    if (scaled.unscaledValue().abs().toString().length() > precision) {
      throw new IllegalArgumentException(
          field + " does not fit DECIMAL(" + precision + "," + scale + ")");
    }
    return scaled;
  }

  /** Fails if {@code value} is longer than the CHAR/VARCHAR width {@code maxLength}. */
  public static String text(String value, int maxLength, String field) {
    if (value != null && value.length() > maxLength) {
      throw new IllegalArgumentException(
          field + " is longer than " + maxLength + " characters (length " + value.length() + ")");
    }
    return value;
  }

  /** Fails if {@code value} is outside {@code 0..max} (unsigned COBOL {@code 9(n)} fields). */
  public static Integer range(Integer value, int max, String field) {
    if (value != null && (value < 0 || value > max)) {
      throw new IllegalArgumentException(field + " must be between 0 and " + max);
    }
    return value;
  }

  /** Fails if a required value is null. */
  public static <T> T required(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
