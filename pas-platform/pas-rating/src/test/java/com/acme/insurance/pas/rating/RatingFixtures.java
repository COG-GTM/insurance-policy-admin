package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * In-memory copy of the seed rows in Flyway V3 (PARITY factors) and V5 (base rates, rating
 * parameters). {@code RatingTablesIT} in pas-policy-service proves the real tables give the same
 * PREMBAT output.
 */
public final class RatingFixtures {

  public static final LocalDate EPOCH = LocalDate.of(1, 1, 1);
  public static final LocalDate RUN_DATE = LocalDate.of(2024, 6, 1);

  private RatingFixtures() {}

  public static RatingParameters prembatParameters() {
    return params("PREMBAT-3200", "0.0350", "25.00", "1.0000");
  }

  public static RatingParameters params(
      String version, String tax, String surcharge, String expMod) {
    return new RatingParameters(
        version,
        new BigDecimal(tax),
        new BigDecimal(surcharge),
        new BigDecimal(expMod),
        new BigDecimal("1.0000"),
        new BigDecimal("0.00"));
  }

  public static InMemoryRatingTables parity() {
    return new InMemoryRatingTables()
        .factorSet(RatingProperties.PARITY)
        .factorSet(RatingProperties.FILED)
        .baseRate("AUT", EPOCH, new BigDecimal("850.00"))
        .baseRate("HOM", EPOCH, new BigDecimal("1200.00"))
        .baseRate("COM", EPOCH, new BigDecimal("5000.00"))
        .baseRate("LIF", EPOCH, new BigDecimal("400.00"))
        .baseRate("HLT", EPOCH, new BigDecimal("3500.00"))
        .baseRate("*", EPOCH, new BigDecimal("1000.00"))
        .parameters(EPOCH, prembatParameters())
        .territoryFactor(RatingProperties.PARITY, "*", EPOCH, new BigDecimal("1.0000"))
        .classFactor(RatingProperties.PARITY, "*", EPOCH, new BigDecimal("1.0000"));
  }

  public static RatingSnapshot paritySnapshot() {
    return parity().load(RUN_DATE, RatingProperties.PARITY);
  }
}
