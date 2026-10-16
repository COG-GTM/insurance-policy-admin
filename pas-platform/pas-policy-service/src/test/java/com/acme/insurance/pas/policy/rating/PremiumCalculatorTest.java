package com.acme.insurance.pas.policy.rating;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PremiumCalculatorTest {

  private final PremiumCalculator calculator = new PremiumCalculator();

  private PremiumCalculation rate(String type) {
    return calculator.rate(
        new PolicyTerm(type, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)));
  }

  @Test
  void baseRatePerType() {
    assertEquals(new BigDecimal("850.00"), rate("AUT").baseRate());
    assertEquals(new BigDecimal("1200.00"), rate("HOM").baseRate());
    assertEquals(new BigDecimal("5000.00"), rate("COM").baseRate());
    assertEquals(new BigDecimal("400.00"), rate("LIF").baseRate());
    assertEquals(new BigDecimal("3500.00"), rate("HLT").baseRate());
  }

  @Test
  void otherVariantsFallToOtherBaseRate() {
    assertEquals(new BigDecimal("1000.00"), rate("CGL").baseRate());
    assertEquals(new BigDecimal("1000.00"), rate("   ").baseRate());
    assertEquals(new BigDecimal("1000.00"), rate("aut").baseRate());
    assertEquals(new BigDecimal("1000.00"), rate("AU ").baseRate());
  }

  @Test
  void autoPremiumBreakdown() {
    PremiumCalculation c = rate("AUT");
    assertEquals(new BigDecimal("29.75"), c.tax());
    assertEquals(new BigDecimal("25.00"), c.surcharge());
    assertEquals(new BigDecimal("904.75"), c.total());
    assertEquals(1, c.coverageSeq());
  }

  @Test
  void taxTruncatesLikeCobolCompute() {
    // COBOL COMPUTE without ROUNDED truncates; 999.99 * 0.0350 = 34.9996500 -> 34.99
    // (verified against the golden: no HALF_UP rounding anywhere in the calc)
    PremiumCalculation c =
        calculator.rate(
            new PolicyTerm("AUT", LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 28)));
    // base 850 * 0.0350 = 29.7500 exactly -> 29.75
    assertEquals(new BigDecimal("29.75"), c.tax());
    assertEquals(LocalDate.of(2024, 2, 29), c.termEffectiveDate());
    assertEquals(LocalDate.of(2025, 2, 28), c.termExpiryDate());
  }
}
