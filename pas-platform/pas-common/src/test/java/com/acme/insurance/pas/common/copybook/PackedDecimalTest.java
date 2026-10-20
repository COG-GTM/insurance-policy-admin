package com.acme.insurance.pas.common.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PackedDecimalTest {

  private static byte[] hex(String h) {
    return HexFormat.of().parseHex(h);
  }

  private static String hex(byte[] b) {
    return HexFormat.of().withUpperCase().formatHex(b);
  }

  @ParameterizedTest(name = "S9({0})V9({1}) {2} <-> {3}")
  @CsvSource({
    // integer digits, scale, value, packed bytes
    "9, 2, 999999999.99, 99999999999C",
    "9, 2, -999999999.99, 99999999999D",
    "9, 2, 0.00, 00000000000C",
    "9, 2, 0.01, 00000000001C",
    "9, 2, -0.01, 00000000001D",
    "9, 2, 1234.56, 00000123456C",
    "7, 2, 9999999.99, 999999999C",
    "7, 2, -9999999.99, 999999999D",
    "11, 2, 99999999999.99, 9999999999999C",
    "11, 2, -99999999999.99, 9999999999999D",
    "7, 4, 9999999.9999, 99999999999C",
    "7, 4, -0.0001, 00000000001D",
    "3, 4, 999.9999, 9999999C",
    "3, 4, 1.0000, 0010000C",
    "3, 4, -999.9999, 9999999D",
    "3, 2, -999.99, 99999D",
    "3, 2, 15.00, 01500C"
  })
  void roundTripsSignedEdgeValues(int integerDigits, int scale, String value, String packed) {
    int digits = integerDigits + scale;
    byte[] out = new byte[PackedDecimal.byteLength(digits)];
    PackedDecimal.encode(new BigDecimal(value), out, 0, digits, scale, true);
    assertThat(hex(out)).isEqualTo(packed);
    BigDecimal decoded = PackedDecimal.decode(out, 0, digits, scale, true);
    assertThat(decoded).isEqualTo(new BigDecimal(value));
    assertThat(decoded.scale()).isEqualTo(scale);
  }

  @Test
  void byteLengthMatchesCompatGateFormula() {
    for (int digits = 1; digits <= 18; digits++) {
      assertThat(PackedDecimal.byteLength(digits)).isEqualTo((int) Math.ceil((digits + 1) / 2.0));
    }
  }

  @Test
  void acceptsAllPreferredAndAlternateSignNibbles() {
    assertThat(PackedDecimal.decode(hex("12345C"), 0, 5, 2, true)).isEqualTo("123.45");
    assertThat(PackedDecimal.decode(hex("12345F"), 0, 5, 2, true)).isEqualTo("123.45");
    assertThat(PackedDecimal.decode(hex("12345D"), 0, 5, 2, true)).isEqualTo("-123.45");
    assertThat(PackedDecimal.decode(hex("12345A"), 0, 5, 2, true)).isEqualTo("123.45");
    assertThat(PackedDecimal.decode(hex("12345E"), 0, 5, 2, true)).isEqualTo("123.45");
    assertThat(PackedDecimal.decode(hex("12345B"), 0, 5, 2, true)).isEqualTo("-123.45");
  }

  @Test
  void negativeZeroDecodesAsZero() {
    assertThat(PackedDecimal.decode(hex("00000D"), 0, 5, 2, true).signum()).isZero();
  }

  @Test
  void unsignedFieldsUseFNibble() {
    byte[] out = new byte[3];
    PackedDecimal.encode(new BigDecimal("123.45"), out, 0, 5, 2, false);
    assertThat(hex(out)).isEqualTo("12345F");
    assertThatThrownBy(() -> PackedDecimal.decode(hex("12345D"), 0, 5, 2, false))
        .isInstanceOf(CopybookDataException.class);
    assertThatThrownBy(() -> PackedDecimal.encode(new BigDecimal("-1"), out, 0, 5, 2, false))
        .isInstanceOf(CopybookDataException.class);
  }

  @Test
  void decodesAtOffsetInsideRecord() {
    byte[] record = hex("40404000001234567C4040");
    assertThat(PackedDecimal.decode(record, 3, 11, 2, true)).isEqualTo("12345.67");
  }

  @Test
  void rejectsOverflowInsteadOfTruncating() {
    byte[] out = new byte[6];
    assertThatThrownBy(
            () -> PackedDecimal.encode(new BigDecimal("1000000000.00"), out, 0, 11, 2, true))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("exceeds 11 digits");
  }

  @Test
  void rejectsExtraFractionDigitsInsteadOfRounding() {
    byte[] out = new byte[6];
    assertThatThrownBy(() -> PackedDecimal.encode(new BigDecimal("1.005"), out, 0, 11, 2, true))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("decimal places");
  }

  @Test
  void rejectsInvalidNibbles() {
    assertThatThrownBy(() -> PackedDecimal.decode(hex("1A345C"), 0, 5, 2, true))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("digit nibble");
    assertThatThrownBy(() -> PackedDecimal.decode(hex("123453"), 0, 5, 2, true))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("sign nibble");
    // even digit count: the high pad nibble must be zero
    assertThatThrownBy(() -> PackedDecimal.decode(hex("11234C"), 0, 4, 2, true))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("pad nibble");
  }

  @Test
  void rejectsFieldOverrunningRecord() {
    assertThatThrownBy(() -> PackedDecimal.decode(new byte[3], 1, 5, 2, true))
        .isInstanceOf(CopybookDataException.class);
  }
}
