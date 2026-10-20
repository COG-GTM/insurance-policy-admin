package com.acme.insurance.pas.rating;

import static com.acme.insurance.pas.rating.RatingFixtures.EPOCH;
import static com.acme.insurance.pas.rating.RatingFixtures.RUN_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.common.domain.PremiumRecord;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PremiumCalculatorTest {

  private static final LocalDate EFF = LocalDate.of(2024, 1, 1);
  private static final LocalDate EXP = LocalDate.of(2025, 1, 1);

  private final PremiumCalculator calculator = new PremiumCalculator();

  private PremiumBreakdown parity(String type) {
    return calculator.rate(RatingFixtures.paritySnapshot(), RatingRequest.of(type, EFF, EXP));
  }

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** Snapshot with one base rate and the given scalar parameters, PARITY factors. */
  private static RatingSnapshot custom(
      String base, String terr, String clazz, String tax, String surcharge, String expMod) {
    return new RatingSnapshot(
        RUN_DATE,
        "TEST",
        Map.of("*", d(base)),
        Map.of("*", d(terr)),
        Map.of("*", d(clazz)),
        RatingFixtures.params("T1", tax, surcharge, expMod));
  }

  private PremiumBreakdown rate(RatingSnapshot s) {
    return calculator.rate(s, RatingRequest.of("AUT", EFF, EXP));
  }

  @ParameterizedTest
  @CsvSource({
    "AUT, 850.00, 29.75, 904.75",
    "HOM, 1200.00, 42.00, 1267.00",
    "COM, 5000.00, 175.00, 5200.00",
    "LIF, 400.00, 14.00, 439.00",
    "HLT, 3500.00, 122.50, 3647.50"
  })
  void everyPolicyTypeAtParity(String type, String base, String tax, String total) {
    PremiumBreakdown r = parity(type);
    assertThat(r.baseRate()).isEqualByComparingTo(base);
    assertThat(r.territoryPremium()).isEqualTo(d(base));
    assertThat(r.classPremium()).isEqualTo(d(base));
    assertThat(r.modifiedPremium()).isEqualTo(d(base));
    assertThat(r.taxAmount()).isEqualTo(d(tax));
    assertThat(r.surchargeAmount()).isEqualTo(d("25.00"));
    assertThat(r.totalPremium()).isEqualTo(d(total));
  }

  @ParameterizedTest
  @ValueSource(strings = {"CGL", "", "aut", "AU", " AU", "Hom", "OTH", "*", "AUTO"})
  void anythingElseFallsToOtherBranch(String type) {
    assertThat(parity(type).baseRate()).isEqualTo(d("1000.00"));
    assertThat(parity(type).totalPremium()).isEqualTo(d("1060.00"));
  }

  @Test
  void trailingBlanksAreInsignificantLikePicX() {
    assertThat(parity("AUT").baseRate()).isEqualTo(parity("AUT ").baseRate());
    assertThat(parity(null).baseRate()).isEqualTo(d("1000.00"));
  }

  @Test
  void breakdownCarriesFactorsVersionAndTerm() {
    PremiumBreakdown r = parity("AUT");
    assertThat(r.coverageSeq()).isEqualTo(1);
    assertThat(r.termEffectiveDate()).isEqualTo(EFF);
    assertThat(r.termExpiryDate()).isEqualTo(EXP);
    assertThat(r.territoryFactor()).isEqualTo(d("1.0000"));
    assertThat(r.classFactor()).isEqualTo(d("1.0000"));
    assertThat(r.experienceMod()).isEqualTo(d("1.0000"));
    assertThat(r.taxRate()).isEqualTo(d("0.0350"));
    assertThat(r.factorSet()).isEqualTo("PARITY");
    assertThat(r.rateVersion()).isEqualTo("PREMBAT-3200");
    assertThat(r.ratingVersion()).isEqualTo("PREMBAT-3200/PARITY");
    assertThat(r.ratingDate()).isEqualTo(RUN_DATE);
  }

  @Test
  void mapsToThePremiumsRowPrembatWrites() {
    PremiumRecord row = parity("HOM").toPremiumRecord("POL0000000042", "PREMBAT");
    assertThat(row.policyNumber()).isEqualTo("POL0000000042");
    assertThat(row.coverageSeq()).isEqualTo(1);
    assertThat(row.baseRate()).isEqualTo(d("1200.0000"));
    assertThat(row.territoryFactor()).isEqualTo(d("1.0000"));
    assertThat(row.classFactor()).isEqualTo(d("1.0000"));
    assertThat(row.experienceMod()).isEqualTo(d("1.0000"));
    assertThat(row.scheduleMod()).isEqualTo(d("1.0000"));
    assertThat(row.discountPct()).isEqualTo(d("0.00"));
    assertThat(row.surchargeAmt()).isEqualTo(d("25.00"));
    assertThat(row.taxAmt()).isEqualTo(d("42.00"));
    assertThat(row.totalPremium()).isEqualTo(d("1267.00"));
    assertThat(row.installmentCode()).isEqualTo("AN");
    assertThat(row.calcDate()).isEqualTo(RUN_DATE);
    assertThat(row.calcBy()).isEqualTo("PREMBAT");
  }

  @Test
  void zeroBaseRateGivesSurchargeOnly() {
    PremiumBreakdown r = rate(custom("0.00", "1.0000", "1.0000", "0.0350", "25.00", "1.0000"));
    assertThat(r.taxAmount()).isEqualTo(d("0.00"));
    assertThat(r.totalPremium()).isEqualTo(d("25.00"));
  }

  @Test
  void zeroTaxAndSurchargeAreAllowed() {
    PremiumBreakdown r = rate(custom("850.00", "1.0000", "1.0000", "0.0000", "0.00", "1.0000"));
    assertThat(r.totalPremium()).isEqualTo(d("850.00"));
  }

  @ParameterizedTest
  @CsvSource({
    // base, tax rate, expected tax (truncated, never rounded up)
    "999.99, 0.0350, 34.99", // 34.99965
    "0.01, 0.0350, 0.00", // 0.00035
    "0.28, 0.0350, 0.00", // 0.0098
    "0.29, 0.0350, 0.01", // 0.01015
    "142.85, 0.0350, 4.99", // 4.99975
    "9999999.99, 9.9999, 99999989.99" // max base x max WS-TAX-RATE = 99999989.9900001 -> overflow
  })
  void taxTruncatesToCents(String base, String taxRate, String expected) {
    RatingSnapshot s = custom(base, "1.0000", "1.0000", taxRate, "25.00", "1.0000");
    if (d(expected).precision() - d(expected).scale() > 7) {
      assertThatThrownBy(() -> rate(s))
          .isInstanceOf(RatingOverflowException.class)
          .hasMessageContaining("WS-TAX-AMOUNT");
    } else {
      assertThat(rate(s).taxAmount()).isEqualTo(d(expected));
    }
  }

  @Test
  void eachFactorStepTruncatesSeparately() {
    // 100.01 x 1.0001 = 100.020001 -> 100.02 ; x 1.0001 = 100.030002 -> 100.03 ; x 0.9999 =
    // 100.019997 -> 100.01 (not 100.02)
    PremiumBreakdown r = rate(custom("100.01", "1.0001", "1.0001", "0.0350", "25.00", "0.9999"));
    assertThat(r.territoryPremium()).isEqualTo(d("100.02"));
    assertThat(r.classPremium()).isEqualTo(d("100.03"));
    assertThat(r.modifiedPremium()).isEqualTo(d("100.01"));
    assertThat(r.taxAmount()).isEqualTo(d("3.50")); // 3.50035
    assertThat(r.totalPremium()).isEqualTo(d("128.51"));
  }

  @Test
  void largestStorableValuesFitTheirPics() {
    // PREM-BASE-RATE S9(7)V9999 caps the base; tax / surcharge S9(7)V99; total S9(9)V99
    PremiumBreakdown r =
        rate(custom("9999999.99", "1.0000", "1.0000", "0.0350", "9999999.99", "1.0000"));
    assertThat(r.taxAmount()).isEqualTo(d("349999.99")); // 349999.99965
    assertThat(r.totalPremium()).isEqualTo(d("20349999.97"));
  }

  @Test
  void baseAboveThePremiumsPicIsRejected() {
    assertThatThrownBy(
            () -> rate(custom("10000000.00", "1.0000", "1.0000", "0.0350", "25.00", "1.0000")))
        .isInstanceOf(RatingOverflowException.class)
        .hasMessageContaining("PREM-BASE-RATE");
  }

  @Test
  void factorProductOverflowingWsPicIsRejectedNotTruncated() {
    // 9999999.99 x 999.9999 x 999.9999 needs 13 integer digits; WS-CLASS-PREMIUM is S9(9)V99
    assertThatThrownBy(
            () -> rate(custom("9999999.99", "999.9999", "999.9999", "0.0350", "25.00", "1.0000")))
        .isInstanceOf(RatingOverflowException.class)
        .hasMessageContaining("WS-");
  }

  @ParameterizedTest
  @CsvSource({
    "0.0000, 1.0000, 0.0350, 25.00, 1.0000, PREM-TERRITORY-FACTOR",
    "1.0000, -1.0000, 0.0350, 25.00, 1.0000, PREM-CLASS-FACTOR",
    "1.0000, 1.0000, 0.0350, 25.00, 0.0000, PREM-EXPERIENCE-MOD",
    "1000.0000, 1.0000, 0.0350, 25.00, 1.0000, PREM-TERRITORY-FACTOR",
    "1.00001, 1.0000, 0.0350, 25.00, 1.0000, PREM-TERRITORY-FACTOR",
    "1.0000, 1.0000, 10.0000, 25.00, 1.0000, WS-TAX-RATE",
    "1.0000, 1.0000, 0.03505, 25.00, 1.0000, WS-TAX-RATE",
    "1.0000, 1.0000, -0.0350, 25.00, 1.0000, WS-TAX-RATE",
    "1.0000, 1.0000, 0.0350, 25.001, 1.0000, WS-SURCHARGE",
    "1.0000, 1.0000, 0.0350, -25.00, 1.0000, WS-SURCHARGE",
    "1.0000, 1.0000, 0.0350, 10000000.00, 1.0000, WS-SURCHARGE"
  })
  void invalidTableValuesAreDataErrors(
      String terr, String clazz, String tax, String surcharge, String mod, String field) {
    assertThatThrownBy(() -> rate(custom("850.00", terr, clazz, tax, surcharge, mod)))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining(field);
  }

  @Test
  void nonNeutralScheduleModOrDiscountIsRejected() {
    RatingParameters sched =
        new RatingParameters("S", d("0.0350"), d("25.00"), d("1.0000"), d("1.1000"), d("0.00"));
    RatingParameters disc =
        new RatingParameters("D", d("0.0350"), d("25.00"), d("1.0000"), d("1.0000"), d("5.00"));
    for (RatingParameters p : new RatingParameters[] {sched, disc}) {
      RatingSnapshot s =
          new RatingSnapshot(
              RUN_DATE, "T", Map.of("*", d("1.00")), Map.of("*", d("1")), Map.of("*", d("1")), p);
      assertThatThrownBy(() -> rate(s))
          .isInstanceOf(RatingDataException.class)
          .hasMessageContaining("not applied by PREMBAT");
    }
  }

  @Test
  void filedFactorsApplyPerTerritoryAndClassWhenLoaded() {
    RatingSnapshot filed =
        RatingFixtures.parity()
            .territoryFactor("FILED", "NY0001", EPOCH, d("1.2500"))
            .territoryFactor("FILED", "*", EPOCH, d("1.0000"))
            .classFactor("FILED", "C01", EPOCH, d("0.9000"))
            .classFactor("FILED", "*", EPOCH, d("1.1000"))
            .load(RUN_DATE, "FILED");
    PremiumBreakdown r =
        calculator.rate(filed, new RatingRequest("AUT", "NY0001", "C01", EFF, EXP));
    // 850.00 x 1.25 = 1062.50 ; x 0.90 = 956.25 ; tax 33.46875 -> 33.46
    assertThat(r.territoryFactor()).isEqualTo(d("1.2500"));
    assertThat(r.classFactor()).isEqualTo(d("0.9000"));
    assertThat(r.territoryPremium()).isEqualTo(d("1062.50"));
    assertThat(r.classPremium()).isEqualTo(d("956.25"));
    assertThat(r.taxAmount()).isEqualTo(d("33.46"));
    assertThat(r.totalPremium()).isEqualTo(d("1014.71"));
    assertThat(r.ratingVersion()).isEqualTo("PREMBAT-3200/FILED");

    PremiumBreakdown fallback =
        calculator.rate(filed, new RatingRequest("AUT", "CA0002", "C99", EFF, EXP));
    assertThat(fallback.territoryFactor()).isEqualTo(d("1.0000"));
    assertThat(fallback.classFactor()).isEqualTo(d("1.1000"));
  }

  @Test
  void parityIgnoresTerritoryAndClassCodes() {
    PremiumBreakdown r =
        calculator.rate(
            RatingFixtures.paritySnapshot(), new RatingRequest("COM", "NY0001", "C01", EFF, EXP));
    assertThat(r.totalPremium()).isEqualTo(parity("COM").totalPremium());
  }

  @Test
  void missingFactorWithoutCatchAllIsADataError() {
    RatingSnapshot s =
        RatingFixtures.parity()
            .territoryFactor("FILED", "NY0001", EPOCH, d("1.2500"))
            .classFactor("FILED", "C01", EPOCH, d("0.9000"))
            .load(RUN_DATE, "FILED");
    assertThatThrownBy(() -> calculator.rate(s, new RatingRequest("AUT", "XX", "C01", EFF, EXP)))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("territory factor for 'XX'");
    assertThatThrownBy(() -> calculator.rate(s, RatingRequest.of("AUT", EFF, EXP)))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("territory factor for ''");
  }
}
