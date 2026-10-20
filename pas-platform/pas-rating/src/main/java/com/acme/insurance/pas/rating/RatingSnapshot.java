package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, fully resolved rating tables for one as-of date and factor set.
 *
 * <p>Codes are compared the way COBOL compares PIC X fields: trailing blanks are insignificant,
 * case and leading blanks are significant. {@value #CATCH_ALL} is the catch-all row: for base rates
 * it is PREMBAT's {@code WHEN OTHER} branch, for territory/class factors it rates any code without
 * its own row.
 */
public final class RatingSnapshot {

  public static final String CATCH_ALL = "*";

  private final LocalDate asOf;
  private final String factorSet;
  private final Map<String, BigDecimal> baseRates;
  private final Map<String, BigDecimal> territoryFactors;
  private final Map<String, BigDecimal> classFactors;
  private final RatingParameters parameters;

  public RatingSnapshot(
      LocalDate asOf,
      String factorSet,
      Map<String, BigDecimal> baseRates,
      Map<String, BigDecimal> territoryFactors,
      Map<String, BigDecimal> classFactors,
      RatingParameters parameters) {
    this.asOf = Objects.requireNonNull(asOf, "asOf");
    this.factorSet = Objects.requireNonNull(factorSet, "factorSet");
    this.baseRates = normalize(baseRates);
    this.territoryFactors = normalize(territoryFactors);
    this.classFactors = normalize(classFactors);
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    if (!this.baseRates.containsKey(CATCH_ALL)) {
      throw new RatingDataException(
          "no catch-all ('*') base rate effective on " + asOf + " (PREMBAT WHEN OTHER branch)");
    }
    if (this.territoryFactors.isEmpty() || this.classFactors.isEmpty()) {
      throw new RatingDataException(
          "factor set "
              + factorSet
              + " has no territory and/or class factors effective on "
              + asOf
              + "; load the tables before selecting it with pas.rating.factor-set");
    }
  }

  private static Map<String, BigDecimal> normalize(Map<String, BigDecimal> rows) {
    Map<String, BigDecimal> out = new java.util.HashMap<>();
    rows.forEach((code, value) -> out.put(key(code), Objects.requireNonNull(value, code)));
    return Map.copyOf(out);
  }

  private static String key(String code) {
    return code == null ? "" : code.stripTrailing();
  }

  public LocalDate asOf() {
    return asOf;
  }

  public String factorSet() {
    return factorSet;
  }

  public RatingParameters parameters() {
    return parameters;
  }

  /** Base rate for an exact policy type, else the catch-all (PREMBAT {@code WHEN OTHER}). */
  public BigDecimal baseRate(String policyType) {
    BigDecimal rate = baseRates.get(key(policyType));
    return rate != null ? rate : baseRates.get(CATCH_ALL);
  }

  public BigDecimal territoryFactor(String territoryCode) {
    return factor("territory", territoryFactors, territoryCode);
  }

  public BigDecimal classFactor(String classCode) {
    return factor("class", classFactors, classCode);
  }

  private BigDecimal factor(String kind, Map<String, BigDecimal> rows, String code) {
    String k = key(code);
    BigDecimal f = k.isEmpty() ? null : rows.get(k);
    if (f == null) {
      f = rows.get(CATCH_ALL);
    }
    if (f == null) {
      throw new RatingDataException(
          "no "
              + kind
              + " factor for '"
              + k
              + "' (and no '*' row) in factor set "
              + factorSet
              + " effective on "
              + asOf);
    }
    return f;
  }
}
