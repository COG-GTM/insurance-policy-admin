package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import com.acme.insurance.pas.domain.RatingParameterCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Rates used by {@link PremiumCalculator}: base premium per policy type, the base for any other
 * type (PREMBAT {@code WHEN OTHER}), the premium tax rate and the flat surcharge. Built from the
 * reference tables PASAPP.BASE_RATES and PASAPP.RATING_PARAMETERS with {@link #from}; no rate is
 * held in code (quirk Q-27).
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
    this.taxRate = Objects.requireNonNull(taxRate, "taxRate").setScale(4, RoundingMode.UNNECESSARY);
    this.surcharge = money(surcharge);
  }

  /**
   * The rating table in force for one rating date, from the rows the reference tables return for
   * that date (one row per key). Fails with {@link RatingDataException} if a parameter is missing
   * or a key appears twice: rating never falls back to a default rate.
   */
  public static RatingTable from(
      Collection<BaseRate> baseRates, Collection<RatingParameter> params) {
    Map<String, BigDecimal> rates = new HashMap<>();
    for (BaseRate rate : baseRates) {
      if (rates.put(rate.policyType(), rate.baseRate()) != null) {
        throw new RatingDataException(
            "more than one base rate for policy type " + rate.policyType());
      }
    }
    Map<RatingParameterCode, BigDecimal> values = new EnumMap<>(RatingParameterCode.class);
    for (RatingParameter param : params) {
      if (values.put(param.code(), param.value()) != null) {
        throw new RatingDataException("more than one value for rating parameter " + param.code());
      }
    }
    for (RatingParameterCode code : RatingParameterCode.values()) {
      if (!values.containsKey(code)) {
        throw new RatingDataException("rating parameter " + code + " has no row in force");
      }
    }
    return new RatingTable(
        rates,
        values.get(RatingParameterCode.OTHER_BASE_RATE),
        values.get(RatingParameterCode.TAX_RATE),
        values.get(RatingParameterCode.SURCHARGE_AMT));
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
