package com.acme.insurance.pas.common.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void ofNormalizesToScaleTwoHalfUp() {
    assertEquals(new BigDecimal("10.01"), Money.of(new BigDecimal("10.005")).toBigDecimal());
    assertEquals(new BigDecimal("10.00"), Money.of("10.004").toBigDecimal());
    assertEquals(new BigDecimal("0.00"), Money.zero().toBigDecimal());
  }

  @Test
  void addAndSubtractKeepScale() {
    Money a = Money.of("10.10");
    Money b = Money.of("0.05");
    assertEquals(Money.of("10.15"), a.add(b));
    assertEquals(Money.of("10.05"), a.subtract(b));
  }

  @Test
  void multiplyRoundsHalfUp() {
    // 7.125 % -> COBOL ROUNDED semantics: 0.072
    assertEquals(Money.of("0.07"), Money.of("1.00").multiply(new BigDecimal("0.07125")));
    assertEquals(Money.of("0.08"), Money.of("1.00").multiply(new BigDecimal("0.075")));
  }

  @Test
  void nullArgumentsRejected() {
    assertThrows(NullPointerException.class, () -> Money.of((BigDecimal) null));
    assertThrows(NullPointerException.class, () -> Money.of((String) null));
  }

  @Test
  void signAndEquality() {
    assertTrue(Money.of("-1.00").isNegative());
    assertFalse(Money.of("1.00").isNegative());
    assertEquals(Money.of("2.50"), Money.of(new BigDecimal("2.5")));
  }
}
