package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PremiumCalculatorTest {

  private static final RatingTable TABLE =
      new RatingTable(
          Map.of("AUT", new BigDecimal("850.00"), "HOM", new BigDecimal("333.33")),
          new BigDecimal("1000.00"),
          new BigDecimal("0.0350"),
          new BigDecimal("25.00"));

  private final PremiumCalculator calculator = new PremiumCalculator(TABLE);

  @Test
  void ratesKnownTypeWithNeutralFactors() {
    PremiumBreakdown premium = calculator.calculate("AUT", RatingFactors.NEUTRAL);

    assertThat(premium.basePremium()).isEqualByComparingTo("850.00");
    assertThat(premium.modifiedPremium()).isEqualByComparingTo("850.00");
    assertThat(premium.tax()).isEqualByComparingTo("29.75");
    assertThat(premium.surcharge()).isEqualByComparingTo("25.00");
    assertThat(premium.totalPremium()).isEqualByComparingTo("904.75");
  }

  @Test
  void unknownBlankAndLowerCaseTypesTakeTheOtherBase() {
    for (String type : new String[] {"XYZ", "   ", "aut", "AU"}) {
      assertThat(calculator.calculate(type, RatingFactors.NEUTRAL).basePremium())
          .as(type)
          .isEqualByComparingTo("1000.00");
    }
  }

  @Test
  void taxIsTruncatedToCentsNotRounded() {
    // 333.33 * 0.0350 = 11.66655: COBOL truncates to 11.66 (HALF_UP would give 11.67).
    PremiumBreakdown premium = calculator.calculate("HOM", RatingFactors.NEUTRAL);

    assertThat(premium.tax()).isEqualByComparingTo("11.66");
    assertThat(premium.totalPremium()).isEqualByComparingTo("369.99");
  }

  @Test
  void everyFactorStepIsTruncated() {
    RatingFactors factors =
        new RatingFactors(
            new BigDecimal("1.0333"),
            new BigDecimal("0.9999"),
            new BigDecimal("1.1111"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.00"));

    PremiumBreakdown premium = calculator.calculate("AUT", factors);

    // 850.00 * 1.0333 = 878.305 -> 878.30; * 0.9999 = 878.2121.. -> 878.21; * 1.1111 = 975.779..
    assertThat(premium.territoryPremium()).isEqualByComparingTo("878.30");
    assertThat(premium.classPremium()).isEqualByComparingTo("878.21");
    assertThat(premium.modifiedPremium()).isEqualByComparingTo("975.77");
    assertThat(premium.tax()).isEqualByComparingTo("34.15");
    assertThat(premium.totalPremium()).isEqualByComparingTo("1034.92");
  }

  @Test
  void ratesMustBeWholeCents() {
    assertThatThrownBy(
            () ->
                new RatingTable(
                    Map.of("AUT", new BigDecimal("850.001")),
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    BigDecimal.ONE))
        .isInstanceOf(ArithmeticException.class);
  }
}
