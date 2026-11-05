package com.acme.insurance.pas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ConstraintsTest {

  @Test
  void normalisesScaleWithoutRounding() {
    assertThat(Constraints.decimal(new BigDecimal("5"), 11, 2, "x")).hasToString("5.00");
    assertThat(Constraints.decimal(new BigDecimal("999999999.99"), 11, 2, "x"))
        .isEqualByComparingTo("999999999.99");
    assertThatThrownBy(() -> Constraints.decimal(new BigDecimal("0.125"), 11, 2, "x"))
        .hasMessageContaining("decimal places");
    assertThatThrownBy(() -> Constraints.decimal(new BigDecimal("1000000000"), 11, 2, "x"))
        .hasMessageContaining("DECIMAL(11,2)");
  }

  @Test
  void auditColumnsStayEightCharacters() {
    assertThatThrownBy(
            () ->
                new Premium(
                    "P",
                    1,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "user@corp"))
        .hasMessageContaining("calcBy is longer than 8");
  }

  @Test
  void ratingFactorsMustBePositiveWithFourDecimals() {
    TerritoryFactor factor =
        new TerritoryFactor("T00001", LocalDate.of(2024, 1, 1), BigDecimal.ONE);

    assertThat(factor.ratingFactor()).isEqualTo(RatingFactors.NEUTRAL);
    assertThatThrownBy(() -> new ClassFactor("C1", LocalDate.of(2024, 1, 1), BigDecimal.ZERO))
        .hasMessageContaining("greater than zero");
    assertThatThrownBy(
            () -> new ClassFactor("C1", LocalDate.of(2024, 1, 1), new BigDecimal("1000")))
        .hasMessageContaining("DECIMAL(7,4)");
  }
}
