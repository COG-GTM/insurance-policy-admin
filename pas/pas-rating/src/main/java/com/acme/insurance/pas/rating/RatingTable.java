package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Rates used by {@link PremiumCalculator}: base premium per policy type, the base for any other
 * type (PREMBAT {@code WHEN OTHER}), the premium tax rate and the flat surcharge. Supplied by the
 * caller; AB-522 loads them from the reference tables.
 */
public final class RatingTable {

  private final Map<String, BigDecimal> baseRateByPolicyType;
  private final BigDecimal otherBaseRate;
  private final BigDecimal taxRate;
  private final BigDecimal surcharge;

  public RatingTable(
      Map<String, BigDecimal> baseRateByPolicyType,
      BigDecimal otherBaseRate,
      BigDecimal taxRate,
      BigDecimal surcharge) {
    Map<String, BigDecimal> rates = new HashMap<>();
    baseRateByPolicyType.forEach((type, rate) -> rates.put(type, money(rate)));
    this.baseRateByPolicyType = Map.copyOf(rates);
    this.otherBaseRate = money(otherBaseRate);
    this.taxRate = Objects.requireNonNull(taxRate, "taxRate");
    this.surcharge = money(surcharge);
  }

  /** Base premium for a policy type code; an unknown code takes the {@code WHEN OTHER} base. */
  public BigDecimal baseRate(String policyType) {
    return baseRateByPolicyType.getOrDefault(policyType, otherBaseRate);
  }

  public BigDecimal taxRate() {
    return taxRate;
  }

  public BigDecimal surcharge() {
    return surcharge;
  }

  private static BigDecimal money(BigDecimal amount) {
    return Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.UNNECESSARY);
  }
}
