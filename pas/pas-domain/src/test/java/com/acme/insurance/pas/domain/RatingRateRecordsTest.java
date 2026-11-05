package com.acme.insurance.pas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RatingRateRecordsTest {

  static final LocalDate DAY = LocalDate.of(2025, 1, 1);

  @Test
  void baseRateIsAThreeCharacterCodeWithAWholeCentRate() {
    assertThat(new BaseRate("AUT", DAY, new BigDecimal("850")).baseRate()).hasToString("850.00");
    assertThat(new BaseRate("AUT", DAY, new BigDecimal("9999999.99")).baseRate())
        .hasToString("9999999.99");
    for (String code : new String[] {"AU", "AUTO", "   "}) {
      assertThatThrownBy(() -> new BaseRate(code, DAY, BigDecimal.ONE))
          .as(code)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new BaseRate("AUT", DAY, new BigDecimal("850.001")))
        .hasMessageContaining("decimal places");
    assertThatThrownBy(() -> new BaseRate("AUT", DAY, new BigDecimal("10000000.00")))
        .hasMessageContaining("DECIMAL(9,2)");
    assertThatThrownBy(() -> new BaseRate("AUT", DAY, new BigDecimal("-0.01")))
        .hasMessageContaining("negative");
  }

  @Test
  void parametersFollowTheirCobolFieldScale() {
    assertThat(
            new RatingParameter(RatingParameterCode.TAX_RATE, DAY, new BigDecimal("0.035")).value())
        .hasToString("0.0350");
    assertThat(
            new RatingParameter(RatingParameterCode.SURCHARGE_AMT, DAY, new BigDecimal("25"))
                .value())
        .hasToString("25.00");
    assertThatThrownBy(
            () -> new RatingParameter(RatingParameterCode.TAX_RATE, DAY, new BigDecimal("10.0000")))
        .hasMessageContaining("DECIMAL(5,4)");
    assertThatThrownBy(
            () ->
                new RatingParameter(
                    RatingParameterCode.SURCHARGE_AMT, DAY, new BigDecimal("25.001")))
        .hasMessageContaining("decimal places");
    assertThatThrownBy(
            () ->
                new RatingParameter(RatingParameterCode.OTHER_BASE_RATE, DAY, new BigDecimal("-1")))
        .hasMessageContaining("negative");
  }
}
