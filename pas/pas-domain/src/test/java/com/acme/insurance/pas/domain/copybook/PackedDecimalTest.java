package com.acme.insurance.pas.domain.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PackedDecimalTest {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  @ParameterizedTest(name = "{0} as S9({1})V9({2}) = {3}")
  @CsvSource({
    "0.00, 9, 2, 00000000000C",
    "1.00, 9, 2, 00000000100C",
    "-0.01, 9, 2, 00000000001D",
    "999999999.99, 9, 2, 99999999999C",
    "-999999999.99, 9, 2, 99999999999D",
    "99999999999.99, 11, 2, 9999999999999C",
    "1234567.89, 7, 2, 123456789C",
    "999.9999, 3, 4, 9999999C",
    "1.0000, 3, 4, 0010000C",
    "9999999.9999, 7, 4, 99999999999C",
    "-999.99, 3, 2, 99999D",
  })
  void encodesAndDecodesEdgeValues(String value, int integerDigits, int scale, String hex) {
    BigDecimal decimal = new BigDecimal(value);
    byte[] packed = PackedDecimal.encode(decimal, integerDigits, scale);

    assertThat(HEX.formatHex(packed)).isEqualTo(hex);
    assertThat(packed).hasSize(PackedDecimal.byteLength(integerDigits + scale));
    BigDecimal decoded =
        PackedDecimal.decode(packed, 0, packed.length, integerDigits + scale, scale);
    assertThat(decoded).isEqualTo(decimal).hasScaleOf(scale);
  }

  @Test
  void evenDigitCountsKeepAZeroPadNibble() {
    byte[] packed = PackedDecimal.encode(new BigDecimal("12345.67"), 6, 2);

    assertThat(HEX.formatHex(packed)).isEqualTo("001234567C");
  }

  @Test
  void acceptsAlternativeSignNibbles() {
    assertThat(PackedDecimal.decode(HEX.parseHex("12345F"), 0, 3, 5, 2))
        .isEqualTo(new BigDecimal("123.45"));
    assertThat(PackedDecimal.decode(HEX.parseHex("12345B"), 0, 3, 5, 2))
        .isEqualTo(new BigDecimal("-123.45"));
    assertThat(PackedDecimal.decode(HEX.parseHex("12345A"), 0, 3, 5, 2)).isPositive();
    assertThat(PackedDecimal.decode(HEX.parseHex("12345E"), 0, 3, 5, 2)).isPositive();
  }

  @Test
  void negativeZeroReadsAsZeroAndIsRewrittenPositive() {
    BigDecimal zero = PackedDecimal.decode(HEX.parseHex("00000D"), 0, 3, 5, 2);

    assertThat(zero.signum()).isZero();
    assertThat(HEX.formatHex(PackedDecimal.encode(zero, 3, 2))).isEqualTo("00000C");
  }

  @Test
  void rejectsInvalidNibbles() {
    assertThatThrownBy(() -> PackedDecimal.decode(HEX.parseHex("1A345C"), 0, 3, 5, 2))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("digit nibble");
    assertThatThrownBy(() -> PackedDecimal.decode(HEX.parseHex("123459"), 0, 3, 5, 2))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("sign nibble");
    assertThatThrownBy(() -> PackedDecimal.decode(HEX.parseHex("40404040"), 0, 4, 7, 2))
        .isInstanceOf(CopybookDataException.class);
  }

  @Test
  void rejectsDigitsInTheHighOrderPadNibble() {
    assertThatThrownBy(() -> PackedDecimal.decode(HEX.parseHex("101234567C"), 0, 5, 8, 2))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("pad nibble");
  }

  @Test
  void rejectsValuesThatCobolWouldTruncate() {
    assertThatThrownBy(() -> PackedDecimal.encode(new BigDecimal("1000000000.00"), 9, 2))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("more than 11 digits");
    assertThatThrownBy(() -> PackedDecimal.encode(new BigDecimal("1.005"), 9, 2))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("decimal places");
  }

  @Test
  void padsShorterScales() {
    byte[] packed = PackedDecimal.encode(new BigDecimal("7"), 3, 4);

    assertThat(PackedDecimal.decode(packed, 0, packed.length, 7, 4)).hasToString("7.0000");
  }
}
