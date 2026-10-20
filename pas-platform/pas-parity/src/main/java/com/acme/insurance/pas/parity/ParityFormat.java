package com.acme.insurance.pas.parity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Output formatters matching the COBOL edited-picture output produced by HXPROC.cpy: money {@code
 * -(13)9.99}, rates with 4 or 6 decimals, plain integers, and trailing-space-trimmed text.
 */
public final class ParityFormat {

  private static final DateTimeFormatter COBOL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  private ParityFormat() {}

  /** Two-decimal amount, e.g. {@code "1267.00"}, {@code "0.00"}, {@code "-0.05"}. */
  public static String money(BigDecimal value) {
    return decimal(value, 2);
  }

  /** Fixed-scale decimal; throws if the value would need rounding. */
  public static String decimal(BigDecimal value, int scale) {
    return value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
  }

  public static String integer(long value) {
    return Long.toString(value);
  }

  /** Text with trailing spaces stripped (all-blank becomes empty). */
  public static String text(String value) {
    return value == null ? "" : value.stripTrailing();
  }

  /** COBOL PIC 9(8) date, e.g. {@code "20240229"}. */
  public static String date(LocalDate value) {
    return value.format(COBOL_DATE);
  }
}
