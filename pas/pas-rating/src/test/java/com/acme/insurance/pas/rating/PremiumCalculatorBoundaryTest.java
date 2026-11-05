package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Boundaries of PREMBAT arithmetic: sub-cent truncation (Q-06) and COBOL field widths (Q-522-1).
 */
class PremiumCalculatorBoundaryTest {

  private static PremiumCalculator calculator(String base, String taxRate, String surcharge) {
    return new PremiumCalculator(
        new RatingTable(
            Map.of("AUT", new BigDecimal(base)),
            new BigDecimal("1000.00"),
            new BigDecimal(taxRate),
            new BigDecimal(surcharge)));
  }

  @ParameterizedTest(name = "base {0}: tax {1}, total {2}")
  @CsvSource({
    // base, tax (truncated), total; the comment is the exact product base * 0.0350
    "0.00, 0.00, 25.00", // zero premium still carries the surcharge
    "0.01, 0.00, 25.01", // 0.00035
    "0.28, 0.00, 25.28", // 0.0098: largest base with no tax
    "0.29, 0.01, 25.30", // 0.01015
    "14.28, 0.49, 39.77", // 0.4998: HALF_UP would give 0.50
    "999.99, 34.99, 1059.98", // 34.99965: HALF_UP would give 35.00
    "1000.99, 35.03, 1061.02", // 35.03465
    "1428.57, 49.99, 1503.56", // 49.99995: one ten-thousandth below 50.00
    "1428.58, 50.00, 1503.58", // 50.0003
  })
  void taxIsTruncatedAtEveryCentBoundary(String base, String tax, String total) {
    PremiumBreakdown premium =
        calculator(base, "0.0350", "25.00").calculate("AUT", RatingFactors.NEUTRAL);

    assertThat(premium.tax()).hasToString(tax);
    assertThat(premium.totalPremium()).hasToString(total);
    assertThat(premium.taxRate()).hasToString("0.0350");
  }

  @Test
  void factorStepsTruncateTowardZeroBeforeTheNextStep() {
    // 0.01 * 0.9999 = 0.009999 -> 0.00; the following steps see 0.00, not 0.009999.
    RatingFactors factors =
        new RatingFactors(
            new BigDecimal("0.9999"),
            new BigDecimal("999.9999"),
            new BigDecimal("999.9999"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.00"));

    PremiumBreakdown premium = calculator("0.01", "0.0350", "25.00").calculate("AUT", factors);

    assertThat(premium.territoryPremium()).hasToString("0.00");
    assertThat(premium.classPremium()).hasToString("0.00");
    assertThat(premium.modifiedPremium()).hasToString("0.00");
    assertThat(premium.totalPremium()).hasToString("25.00");
  }

  @Test
  void largestPremiumThatFitsWsFinalPremium() {
    // 9999999.99 + 349999.99 + 0.00 fits S9(09)V99.
    PremiumBreakdown premium =
        calculator("9999999.99", "0.0350", "0.00").calculate("AUT", RatingFactors.NEUTRAL);
    assertThat(premium.tax()).hasToString("349999.99");
    assertThat(premium.totalPremium()).hasToString("10349999.98");
  }

  @Test
  void anIntermediatePremiumThatOverflowsS9_09_V99Fails() {
    RatingFactors big =
        new RatingFactors(
            new BigDecimal("999.9999"),
            new BigDecimal("999.9999"),
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.00"));
    // 9999999.99 * 999.9999 needs 10 integer digits.
    assertThatThrownBy(() -> calculator("9999999.99", "0.0350", "0.00").calculate("AUT", big))
        .isInstanceOf(RatingOverflowException.class)
        .hasMessageContaining("WS-TERR-PREMIUM");
  }

  @Test
  void taxThatOverflowsS9_07_V99Fails() {
    RatingFactors factors =
        new RatingFactors(
            new BigDecimal("100.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.00"));
    // modified premium 999999999.00 fits; tax 34999999.96 needs 8 integer digits.
    assertThatThrownBy(() -> calculator("9999999.99", "0.0350", "0.00").calculate("AUT", factors))
        .isInstanceOf(RatingOverflowException.class)
        .hasMessageContaining("WS-TAX-AMOUNT");
  }

  @Test
  void factorsOutsideS9_03_V9999AreRejected() {
    for (String bad : new String[] {"0.0000", "-1.0000", "1.00001", "1000.0000"}) {
      assertThatThrownBy(
              () ->
                  RatingFactors.territoryAndClass(
                      new BigDecimal(bad), RatingFactors.NEUTRAL.classFactor()))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void breakdownMapsToThePremiumsRowPrembatWrites() {
    PremiumBreakdown premium =
        calculator("850.00", "0.0350", "25.00").calculate("AUT", RatingFactors.NEUTRAL);

    var row =
        premium.toPremium(
            "POL-00000001",
            1,
            java.time.LocalDate.of(2025, 1, 1),
            java.time.LocalDate.of(2026, 1, 1),
            java.time.LocalDate.of(2025, 3, 1),
            "PREMBAT");

    assertThat(row.baseRate()).hasToString("850.0000");
    assertThat(row.territoryFactor()).hasToString("1.0000");
    assertThat(row.discountPct()).hasToString("0.00");
    assertThat(row.taxAmt()).hasToString("29.75");
    assertThat(row.surchargeAmt()).hasToString("25.00");
    assertThat(row.totalPremium()).hasToString("904.75");
    assertThat(row.installmentCode()).isEqualTo("AN");
    assertThat(row.installmentAmt()).isNull();
    assertThat(row.calcBy()).isEqualTo("PREMBAT");
  }
}
