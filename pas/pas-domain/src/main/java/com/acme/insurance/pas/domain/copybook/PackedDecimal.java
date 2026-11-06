package com.acme.insurance.pas.domain.copybook;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * COMP-3 (packed decimal) conversion. Each byte holds two decimal digits; the low nibble of the
 * last byte is the sign: C (or A, E, F) positive, D (or B) negative. Encoding always writes C or D,
 * the preferred signs IBM compilers produce for signed fields.
 */
public final class PackedDecimal {

  private PackedDecimal() {}

  /** Bytes needed for {@code digits} digits plus the sign nibble. */
  public static int byteLength(int digits) {
    return digits / 2 + 1;
  }

  /**
   * Encodes {@code value} as {@code S9(integerDigits)V9(scale) COMP-3}. Values that would lose
   * decimal places or high-order digits are rejected: COBOL would truncate them silently (Q-06), so
   * the caller must round explicitly first.
   */
  public static byte[] encode(BigDecimal value, int integerDigits, int scale) {
    int digits = integerDigits + scale;
    BigDecimal scaled;
    try {
      scaled = value.setScale(scale, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new CopybookDataException("value has more than " + scale + " decimal places", e);
    }
    String unscaled = scaled.unscaledValue().abs().toString();
    if (unscaled.length() > digits) {
      throw new CopybookDataException(
          "value needs more than "
              + digits
              + " digits (S9("
              + integerDigits
              + ")V9("
              + scale
              + "))");
    }
    byte[] out = new byte[byteLength(digits)];
    int digitNibbles = out.length * 2 - 1;
    int pad = digitNibbles - unscaled.length();
    for (int i = 0; i < digitNibbles; i++) {
      int nibble = i < pad ? 0 : unscaled.charAt(i - pad) - '0';
      setNibble(out, i, nibble);
    }
    setNibble(out, digitNibbles, scaled.signum() < 0 ? 0xD : 0xC);
    return out;
  }

  /** Decodes {@code length} bytes at {@code offset} as a packed decimal with {@code scale}. */
  public static BigDecimal decode(byte[] data, int offset, int length, int digits, int scale) {
    if (length != byteLength(digits)) {
      throw new IllegalArgumentException(
          "length " + length + " does not hold " + digits + " digits");
    }
    int digitNibbles = length * 2 - 1;
    int pad = digitNibbles - digits;
    StringBuilder text = new StringBuilder(digitNibbles);
    for (int i = 0; i < digitNibbles; i++) {
      int nibble = nibble(data, offset, i);
      if (nibble > 9) {
        throw new CopybookDataException("invalid digit nibble " + Integer.toHexString(nibble));
      }
      if (i < pad && nibble != 0) {
        throw new CopybookDataException("high-order pad nibble is not zero");
      }
      text.append((char) ('0' + nibble));
    }
    int sign = nibble(data, offset, digitNibbles);
    boolean negative =
        switch (sign) {
          case 0xA, 0xC, 0xE, 0xF -> false;
          case 0xB, 0xD -> true;
          default ->
              throw new CopybookDataException("invalid sign nibble " + Integer.toHexString(sign));
        };
    BigInteger unscaled = new BigInteger(text.toString());
    return new BigDecimal(negative ? unscaled.negate() : unscaled, scale);
  }

  private static int nibble(byte[] data, int offset, int index) {
    int b = data[offset + index / 2] & 0xFF;
    return index % 2 == 0 ? b >>> 4 : b & 0x0F;
  }

  private static void setNibble(byte[] out, int index, int nibble) {
    if (index % 2 == 0) {
      out[index / 2] = (byte) ((out[index / 2] & 0x0F) | (nibble << 4));
    } else {
      out[index / 2] = (byte) ((out[index / 2] & 0xF0) | nibble);
    }
  }
}
