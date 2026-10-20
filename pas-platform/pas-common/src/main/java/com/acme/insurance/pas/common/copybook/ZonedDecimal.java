package com.acme.insurance.pas.common.copybook;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * DISPLAY numeric codec ({@code PIC 9(n)V9(m)} and {@code PIC S9(n)V9(m)}), working on characters
 * so it is independent of the record charset. Signed fields use the IBM default trailing overpunch:
 * the last digit carries the sign as {@code {A..I} = +0..+9} and {@code }J..R} = -0..-9}, which are
 * exactly the EBCDIC zone bytes {@code C0..C9}/{@code D0..D9}. Unsigned trailing {@code 0..9} is
 * accepted on decode as positive.
 */
public final class ZonedDecimal {

  private static final String POSITIVE = "{ABCDEFGHI";
  private static final String NEGATIVE = "}JKLMNOPQR";

  private ZonedDecimal() {}

  public static BigDecimal decode(String text, int digits, int scale, boolean signed) {
    if (text.length() != digits) {
      throw new CopybookDataException("expected " + digits + " digits, got " + text.length());
    }
    StringBuilder sb = new StringBuilder(text);
    boolean negative = false;
    char last = text.charAt(digits - 1);
    if (signed && (POSITIVE.indexOf(last) >= 0 || NEGATIVE.indexOf(last) >= 0)) {
      int p = POSITIVE.indexOf(last);
      negative = p < 0;
      sb.setCharAt(digits - 1, (char) ('0' + (negative ? NEGATIVE.indexOf(last) : p)));
    }
    for (int i = 0; i < digits; i++) {
      char c = sb.charAt(i);
      if (c < '0' || c > '9') {
        throw new CopybookDataException("non-numeric character in DISPLAY numeric field");
      }
    }
    BigInteger unscaled = new BigInteger(sb.toString());
    return new BigDecimal(negative ? unscaled.negate() : unscaled, scale);
  }

  public static String encode(BigDecimal value, int digits, int scale, boolean signed) {
    BigDecimal scaled = Decimals.rescaleExact(value, scale);
    if (scaled.signum() < 0 && !signed) {
      throw new CopybookDataException("negative value " + value + " for unsigned numeric field");
    }
    String text = scaled.unscaledValue().abs().toString();
    if (text.length() > digits) {
      throw new CopybookDataException(
          "value " + value + " exceeds " + digits + " digits (scale " + scale + ")");
    }
    StringBuilder sb = new StringBuilder("0".repeat(digits - text.length())).append(text);
    if (signed) {
      int d = sb.charAt(digits - 1) - '0';
      sb.setCharAt(digits - 1, (scaled.signum() < 0 ? NEGATIVE : POSITIVE).charAt(d));
    }
    return sb.toString();
  }
}
