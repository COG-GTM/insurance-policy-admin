package com.acme.insurance.pas.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Monetary amount with COBOL {@code ROUNDED} semantics: fixed scale of 2 and {@link
 * RoundingMode#HALF_UP} rounding applied on construction and after multiplicative operations.
 */
public final class Money {

  public static final int SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  private final BigDecimal amount;

  private Money(BigDecimal amount) {
    this.amount = amount;
  }

  public static Money of(BigDecimal amount) {
    Objects.requireNonNull(amount, "amount");
    return new Money(amount.setScale(SCALE, ROUNDING));
  }

  public static Money of(String amount) {
    return of(new BigDecimal(Objects.requireNonNull(amount, "amount")));
  }

  public static Money zero() {
    return of(BigDecimal.ZERO);
  }

  public Money add(Money other) {
    return new Money(this.amount.add(other.amount).setScale(SCALE, ROUNDING));
  }

  public Money subtract(Money other) {
    return new Money(this.amount.subtract(other.amount).setScale(SCALE, ROUNDING));
  }

  public Money multiply(BigDecimal factor) {
    Objects.requireNonNull(factor, "factor");
    return new Money(this.amount.multiply(factor).setScale(SCALE, ROUNDING));
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public BigDecimal toBigDecimal() {
    return amount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Money)) {
      return false;
    }
    return amount.equals(((Money) o).amount);
  }

  @Override
  public int hashCode() {
    return amount.hashCode();
  }

  @Override
  public String toString() {
    return amount.toPlainString();
  }
}
