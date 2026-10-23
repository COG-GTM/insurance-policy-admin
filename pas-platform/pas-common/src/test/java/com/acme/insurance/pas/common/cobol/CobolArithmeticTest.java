package com.acme.insurance.pas.common.cobol;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CobolArithmeticTest {
  @Test
  void storesUnsignedMagnitudeAndTruncatesFractionalDigits() {
    assertThat(CobolArithmetic.storeUnsigned(new BigDecimal("-123.459"), 3, 2))
        .isEqualByComparingTo("123.45");
  }

  @Test
  void keepsLowOrderIntegerDigits() {
    assertThat(CobolArithmetic.storeUnsigned(new BigDecimal("12345.67"), 3, 2))
        .isEqualByComparingTo("345.67");
  }

  @Test
  void storesSignedValuesWithLowOrderIntegerDigits() {
    assertThat(CobolArithmetic.storeSigned(new BigDecimal("-12345.679"), 3, 2))
        .isEqualByComparingTo("-345.67");
  }

  @Test
  void preservesScaleForZero() {
    assertThat(CobolArithmetic.storeUnsigned(new BigDecimal("-0.009"), 3, 2))
        .isEqualTo(new BigDecimal("0.00"));
  }
}
