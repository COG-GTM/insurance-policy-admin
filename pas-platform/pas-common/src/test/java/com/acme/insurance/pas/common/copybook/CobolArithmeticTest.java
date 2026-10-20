package com.acme.insurance.pas.common.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CobolArithmeticTest {

  @Test
  void signedTruncatesDecimalsAndHighOrderDigitsKeepingSign() {
    assertThat(CobolArithmetic.storeSigned(new BigDecimal("80.0030003"), 3, 2))
        .isEqualByComparingTo("80.00")
        .hasToString("80.00");
    assertThat(CobolArithmetic.storeSigned(new BigDecimal("200000"), 3, 2)).hasToString("0.00");
    assertThat(CobolArithmetic.storeSigned(new BigDecimal("1234.567"), 3, 2)).hasToString("234.56");
    assertThat(CobolArithmetic.storeSigned(new BigDecimal("-50.009"), 3, 2)).hasToString("-50.00");
    assertThat(CobolArithmetic.storeSigned(new BigDecimal("-1234.5"), 3, 2)).hasToString("-234.50");
  }

  @Test
  void unsignedDropsTheSign() {
    assertThat(CobolArithmetic.storeUnsigned(new BigDecimal("-49.5"), 4, 0)).hasToString("49");
    assertThat(CobolArithmetic.storeUnsigned(-75, 4)).isEqualTo(75);
    assertThat(CobolArithmetic.storeUnsigned(1150, 3)).isEqualTo(150);
    assertThat(CobolArithmetic.storeUnsigned(1000, 3)).isZero();
    assertThat(CobolArithmetic.storeUnsigned(123_456, 5)).isEqualTo(23_456);
    assertThat(CobolArithmetic.storeUnsigned(Long.MIN_VALUE, 3)).isEqualTo(807);
  }

  @Test
  void rejectsUnsupportedPictures() {
    assertThatThrownBy(() -> CobolArithmetic.storeSigned(BigDecimal.ONE, 0, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CobolArithmetic.storeUnsigned(1, 19))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void wholeIsAnIntegerDecimal() {
    assertThat(CobolArithmetic.whole(5_000_000)).isEqualByComparingTo("5000000");
  }
}
