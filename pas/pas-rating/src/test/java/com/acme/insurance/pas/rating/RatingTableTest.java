package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import com.acme.insurance.pas.domain.RatingParameterCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RatingTableTest {

  static final LocalDate SINCE = LocalDate.of(2020, 1, 1);

  static List<BaseRate> baseRates() {
    return List.of(
        new BaseRate("AUT", SINCE, new BigDecimal("700.00")),
        new BaseRate("HOM", SINCE, new BigDecimal("333.33")));
  }

  static List<RatingParameter> parameters() {
    return List.of(
        new RatingParameter(RatingParameterCode.OTHER_BASE_RATE, SINCE, new BigDecimal("900")),
        new RatingParameter(RatingParameterCode.TAX_RATE, SINCE, new BigDecimal("0.0400")),
        new RatingParameter(RatingParameterCode.SURCHARGE_AMT, SINCE, new BigDecimal("10.00")));
  }

  @Test
  void buildsTheTableFromReferenceRows() {
    RatingTable table = RatingTable.from(baseRates(), parameters());

    assertThat(table.baseRate("AUT")).isEqualByComparingTo("700.00");
    assertThat(table.baseRate("HOM")).isEqualByComparingTo("333.33");
    assertThat(table.baseRate("LIF")).isEqualByComparingTo("900.00");
    assertThat(table.taxRate()).hasToString("0.0400");
    assertThat(table.surcharge()).hasToString("10.00");
  }

  @Test
  void aMissingParameterStopsRatingInsteadOfDefaulting() {
    for (RatingParameterCode missing : RatingParameterCode.values()) {
      List<RatingParameter> params = new ArrayList<>(parameters());
      params.removeIf(p -> p.code() == missing);
      assertThatThrownBy(() -> RatingTable.from(baseRates(), params))
          .isInstanceOf(RatingDataException.class)
          .hasMessageContaining(missing.name());
    }
  }

  @Test
  void duplicateKeysAreRejected() {
    List<BaseRate> rates = new ArrayList<>(baseRates());
    rates.add(new BaseRate("AUT", SINCE.plusDays(1), new BigDecimal("1.00")));
    assertThatThrownBy(() -> RatingTable.from(rates, parameters()))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("AUT");

    List<RatingParameter> params = new ArrayList<>(parameters());
    params.add(new RatingParameter(RatingParameterCode.TAX_RATE, SINCE, new BigDecimal("0.05")));
    assertThatThrownBy(() -> RatingTable.from(baseRates(), params))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("TAX_RATE");
  }

  @Test
  void noBaseRatesMeansEveryTypeTakesTheOtherBase() {
    RatingTable table = RatingTable.from(List.of(), parameters());
    assertThat(table.baseRate("AUT")).isEqualByComparingTo("900.00");
  }

  @Test
  void taxRateKeepsFourDecimalPlaces() {
    assertThatThrownBy(
            () ->
                new RatingTable(
                    java.util.Map.of(), BigDecimal.TEN, new BigDecimal("0.03505"), BigDecimal.ONE))
        .isInstanceOf(ArithmeticException.class);
  }
}
