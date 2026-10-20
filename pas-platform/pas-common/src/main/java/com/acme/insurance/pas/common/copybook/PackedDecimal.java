package com.acme.insurance.pas.common.copybook;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COMP-3 (packed decimal) codec. Each byte holds two BCD digit nibbles; the low nibble of the last
 * byte is the sign: {@code C}/{@code A}/{@code E}/{@code F} positive, {@code D}/{@code B} negative.
 * Encoding writes {@code C}/{@code D} for signed PICs and {@code F} for unsigned ones.
 *
 * <p>Unlike a COBOL {@code MOVE}, encoding never truncates silently: a value with more integer or
 * fractional digits than the PIC allows is rejected.
 */
public final class PackedDecimal {

  private PackedDecimal() {}

  /** Bytes occupied by a packed field with {@code digits} total digits. */
  public static int byteLength(int digits) {
    return digits / 2 + 1;
  }

  public static BigDecimal decode(byte[] data, int offset, int digits, int scale, boolean signed) {
    int length = byteLength(digits);
    if (offset < 0 || offset + length > data.length) {
      throw new CopybookDataException("packed field overruns record at offset " + offset);
    }
    int nibbles = length * 2 - 1;
    int pad = nibbles - digits;
    StringBuilder sb = new StringBuilder(digits);
    for (int i = 0; i < nibbles; i++) {
      int b = data[offset + i / 2] & 0xFF;
      int nibble = (i % 2 == 0) ? b >>> 4 : b & 0x0F;
      if (nibble > 9) {
        throw new CopybookDataException(
            String.format("invalid packed digit nibble 0x%X at offset %d", nibble, offset + i / 2));
      }
      if (i < pad) {
        if (nibble != 0) {
          throw new CopybookDataException(
              "non-zero pad nibble in packed field at offset " + offset);
        }
        continue;
      }
      sb.append((char) ('0' + nibble));
    }
    int sign = data[offset + length - 1] & 0x0F;
    boolean negative =
        switch (sign) {
          case 0x0C, 0x0A, 0x0E, 0x0F -> false;
          case 0x0D, 0x0B -> true;
          default ->
              throw new CopybookDataException(
                  String.format("invalid packed sign nibble 0x%X at offset %d", sign, offset));
        };
    if (negative && !signed) {
      throw new CopybookDataException("negative sign in unsigned packed field at offset " + offset);
    }
    BigInteger unscaled = new BigInteger(sb.toString());
    return new BigDecimal(negative ? unscaled.negate() : unscaled, scale);
  }

  public static void encode(
      BigDecimal value, byte[] target, int offset, int digits, int scale, boolean signed) {
    BigDecimal scaled = Decimals.rescaleExact(value, scale);
    if (scaled.signum() < 0 && !signed) {
      throw new CopybookDataException("negative value " + value + " for unsigned packed field");
    }
    String text = scaled.unscaledValue().abs().toString();
    if (text.length() > digits) {
      throw new CopybookDataException(
          "value " + value + " exceeds " + digits + " digits (scale " + scale + ")");
    }
    int length = byteLength(digits);
    int nibbles = length * 2 - 1;
    StringBuilder padded = new StringBuilder(nibbles);
    padded.append("0".repeat(nibbles - text.length())).append(text);
    int sign = !signed ? 0x0F : scaled.signum() < 0 ? 0x0D : 0x0C;
    for (int i = 0; i < length; i++) {
      int high = padded.charAt(i * 2) - '0';
      int low = (i * 2 + 1 < nibbles) ? padded.charAt(i * 2 + 1) - '0' : sign;
      target[offset + i] = (byte) ((high << 4) | low);
    }
  }
}
