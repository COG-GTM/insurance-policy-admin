package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PremiumCalculatorTest {
  private static final LocalDate TODAY = LocalDate.of(2024, 10, 15);
  private static final LocalDate BASE_DATE = LocalDate.of(1900, 1, 1);

  @Test
  void calculatesQ07LegacyPremiumsAndNormalizesCobolPolicyTypes() {
    InMemoryRatingTableRepository repository = seededRepository();
    PremiumCalculator calculator = new PremiumCalculator(repository, FactorMode.LEGACY);

    assertPremium(calculator, null, "1060.00", "*");
    assertPremium(calculator, "", "1060.00", "*");
    assertPremium(calculator, "aut", "1060.00", "*");
    assertPremium(calculator, "AU", "1060.00", "*");
    assertPremium(calculator, " A ", "1060.00", "*");
    assertPremium(calculator, "XYZ", "1060.00", "*");
    assertPremium(calculator, "AUT ", "904.75", "AUT");
    assertPremium(calculator, "AUT", "904.75", "AUT");
    assertPremium(calculator, "HOM", "1267.00", "HOM");
    assertPremium(calculator, "COM", "5200.00", "COM");
    assertPremium(calculator, "LIF", "439.00", "LIF");
    assertPremium(calculator, "HLT", "3647.50", "HLT");
  }

  @Test
  void prefersExactParametersRegardlessOfFallbackDateAndIgnoresFutureRows() {
    InMemoryRatingTableRepository repository = seededRepository();
    repository.parameters.add(
        parameters("*", LocalDate.of(2024, 1, 1), "2000.00", "1.0000", "1.0000", "0.0000", "0.00"));
    repository.parameters.add(
        parameters(
            "AUT", LocalDate.of(2025, 1, 1), "9000.00", "1.0000", "1.0000", "0.0000", "0.00"));
    repository.parameters.add(
        parameters(
            "AUT", LocalDate.of(2020, 1, 1), "850.00", "1.0000", "1.0000", "0.0350", "25.00"));

    PremiumBreakdown breakdown =
        calculator(repository, FactorMode.LEGACY).calculate(request("AUT"));

    assertThat(breakdown.basePremium()).isEqualByComparingTo("850.00");
    assertThat(breakdown.parametersEffectiveDate()).isEqualTo(LocalDate.of(2020, 1, 1));
    assertThat(breakdown.policyTypeKey()).isEqualTo("AUT");
  }

  @Test
  void truncatesTaxAndEachFactorStepAndKeepsTheTwoFactorStoresSeparate() {
    InMemoryRatingTableRepository repository = seededRepository();
    repository.parameters.clear();
    repository.parameters.add(
        parameters("AUT", BASE_DATE, "999.99", "1.0000", "1.0000", "0.0350", "25.00"));
    PremiumBreakdown tax = calculator(repository, FactorMode.LEGACY).calculate(request("AUT"));
    assertThat(tax.taxAmount()).isEqualByComparingTo("34.99");

    repository.parameters.clear();
    repository.parameters.add(
        parameters("AUT", BASE_DATE, "100.01", "1.0000", "1.0000", "0.0000", "0.00"));
    repository.territoryFactors.clear();
    repository.territoryFactors.add(factor("LEGACY", "NY0001", "1.0005"));
    assertThat(
            calculator(repository, FactorMode.LEGACY)
                .calculate(new RatingRequest("AUT", TODAY, "NY0001", null))
                .territoryPremium())
        .isEqualByComparingTo("100.06");

    repository.parameters.clear();
    repository.parameters.add(
        parameters("AUT", BASE_DATE, "1.00", "1.0000", "1.0000", "0.0000", "0.00"));
    repository.territoryFactors.clear();
    repository.territoryFactors.add(factor("LEGACY", "NY0001", "1.0099"));
    repository.classFactors.clear();
    repository.classFactors.add(classFactor("LEGACY", "AUT", "PPA01", "1.0099"));
    PremiumBreakdown sequential =
        calculator(repository, FactorMode.LEGACY)
            .calculate(new RatingRequest("AUT", TODAY, "NY0001", "PPA01"));
    assertThat(sequential.classPremium()).isEqualByComparingTo("1.00");
    assertThat(
            CobolPic.store(
                new BigDecimal("1.00")
                    .multiply(new BigDecimal("1.0099"))
                    .multiply(new BigDecimal("1.0099")),
                9,
                2,
                "combined"))
        .isEqualByComparingTo("1.01");
  }

  @Test
  void supportsZeroPremiumAndSurchargeOnlyTotal() {
    InMemoryRatingTableRepository repository = seededRepository();
    repository.parameters.clear();
    repository.parameters.add(
        parameters("AUT", BASE_DATE, "0.00", "1.0000", "1.0000", "0.0350", "25.00"));

    PremiumBreakdown breakdown =
        calculator(repository, FactorMode.LEGACY).calculate(request("AUT"));

    assertThat(breakdown.taxAmount()).isEqualByComparingTo("0.00");
    assertThat(breakdown.totalPremium()).isEqualByComparingTo("25.00");
  }

  @Test
  void checksTotalAndTaxPicBoundaries() {
    InMemoryRatingTableRepository repository = seededRepository();
    repository.parameters.clear();
    repository.parameters.add(
        parameters("AUT", BASE_DATE, "999999974.99", "1.0000", "1.0000", "0.0000", "25.00"));
    assertThat(calculator(repository, FactorMode.LEGACY).calculate(request("AUT")).totalPremium())
        .isEqualByComparingTo("999999999.99");
    repository.parameters.set(
        0, parameters("AUT", BASE_DATE, "999999975.00", "1.0000", "1.0000", "0.0000", "25.00"));
    assertThatThrownBy(() -> calculator(repository, FactorMode.LEGACY).calculate(request("AUT")))
        .isInstanceOf(PremiumOverflowException.class)
        .hasMessageContaining("WS-FINAL-PREMIUM");

    repository.parameters.set(
        0, parameters("AUT", BASE_DATE, "285714285.71", "1.0000", "1.0000", "0.0350", "0.00"));
    assertThat(calculator(repository, FactorMode.LEGACY).calculate(request("AUT")).taxAmount())
        .isEqualByComparingTo("9999999.99");
    repository.parameters.set(
        0, parameters("AUT", BASE_DATE, "285714285.72", "1.0000", "1.0000", "0.0350", "0.00"));
    assertThatThrownBy(() -> calculator(repository, FactorMode.LEGACY).calculate(request("AUT")))
        .isInstanceOf(PremiumOverflowException.class)
        .hasMessageContaining("WS-TAX-AMOUNT");
  }

  @Test
  void baseRateIsStoredAtFourDecimalsWithItsOwnPicCapacity() {
    InMemoryRatingTableRepository repository = seededRepository();
    repository.parameters.clear();
    repository.parameters.add(
        parameters("AUT", BASE_DATE, "9999999.99", "1.0000", "1.0000", "0.0000", "0.00"));
    PremiumBreakdown breakdown =
        calculator(repository, FactorMode.LEGACY).calculate(request("AUT"));

    assertThat(breakdown.premiumRecordBaseRate()).isEqualByComparingTo("9999999.9900");
    repository.parameters.set(
        0, parameters("AUT", BASE_DATE, "10000000.00", "1.0000", "1.0000", "0.0000", "0.00"));
    PremiumBreakdown overRecordLimit =
        calculator(repository, FactorMode.LEGACY).calculate(request("AUT"));
    assertThatThrownBy(overRecordLimit::premiumRecordBaseRate)
        .isInstanceOf(PremiumOverflowException.class)
        .hasMessageContaining("PREM-BASE-RATE");
  }

  @Test
  void legacyModeIgnoresActiveSetWhileTablesModeSelectsLatestSpecificFactors() {
    InMemoryRatingTableRepository repository = seededRepository();
    repository.activeFactorSet = "ILLUSTRATIVE";
    repository.territoryFactors.add(factor("ILLUSTRATIVE", "NY0001", "1.2500"));
    repository.territoryFactors.add(factor("ILLUSTRATIVE", "*", "1.0000"));
    repository.classFactors.add(classFactor("ILLUSTRATIVE", "AUT", "PPA01", "1.0500"));
    repository.classFactors.add(classFactor("ILLUSTRATIVE", "AUT", "*", "1.0000"));
    PremiumBreakdown legacy =
        calculator(repository, FactorMode.LEGACY)
            .calculate(new RatingRequest("AUT", TODAY, "NY0001", "PPA01"));
    PremiumBreakdown tables =
        calculator(repository, FactorMode.TABLES)
            .calculate(new RatingRequest("AUT", TODAY, "NY0001", "PPA01"));

    assertThat(legacy.factorSet()).isEqualTo("LEGACY");
    assertThat(legacy.totalPremium()).isEqualByComparingTo("904.75");
    assertThat(tables.factorSet()).isEqualTo("ILLUSTRATIVE");
    assertThat(tables.territoryPremium()).isEqualByComparingTo("1062.50");
    assertThat(tables.classPremium()).isEqualByComparingTo("1115.62");
    assertThat(tables.taxAmount()).isEqualByComparingTo("39.04");
    assertThat(tables.totalPremium()).isEqualByComparingTo("1179.66");
  }

  @Test
  void tablesModePrefersExactRowsAndLatestEligibleEffectiveDates() {
    InMemoryRatingTableRepository repository = seededRepository();
    repository.activeFactorSet = "ILLUSTRATIVE";
    repository.parameters.add(
        parameters("*", LocalDate.of(2024, 1, 1), "2000.00", "1.0000", "1.0000", "0.0000", "0.00"));
    repository.parameters.add(
        parameters(
            "AUT", LocalDate.of(2020, 1, 1), "850.00", "1.0000", "1.0000", "0.0000", "0.00"));
    repository.territoryFactors.add(
        new InMemoryRatingTableRepository.FactorRow(
            "ILLUSTRATIVE", "NY0001", LocalDate.of(2020, 1, 1), new BigDecimal("1.2500")));
    repository.territoryFactors.add(
        new InMemoryRatingTableRepository.FactorRow(
            "ILLUSTRATIVE", "NY0001", LocalDate.of(2022, 1, 1), new BigDecimal("1.2600")));
    repository.territoryFactors.add(
        new InMemoryRatingTableRepository.FactorRow(
            "ILLUSTRATIVE", "*", LocalDate.of(2023, 1, 1), new BigDecimal("1.5000")));
    repository.territoryFactors.add(
        new InMemoryRatingTableRepository.FactorRow(
            "ILLUSTRATIVE", "NY0001", LocalDate.of(2025, 1, 1), new BigDecimal("1.9900")));
    repository.classFactors.add(
        new InMemoryRatingTableRepository.ClassFactorRow(
            "ILLUSTRATIVE", "AUT", "PPA01", LocalDate.of(2020, 1, 1), new BigDecimal("1.0500")));
    repository.classFactors.add(
        new InMemoryRatingTableRepository.ClassFactorRow(
            "ILLUSTRATIVE", "AUT", "PPA01", LocalDate.of(2023, 1, 1), new BigDecimal("1.0600")));
    repository.classFactors.add(
        new InMemoryRatingTableRepository.ClassFactorRow(
            "ILLUSTRATIVE", "*", "PPA01", LocalDate.of(2024, 1, 1), new BigDecimal("1.5000")));

    PremiumBreakdown result =
        calculator(repository, FactorMode.TABLES)
            .calculate(new RatingRequest("AUT", TODAY, "NY0001", "PPA01"));

    assertThat(result.policyTypeKey()).isEqualTo("AUT");
    assertThat(result.parametersEffectiveDate()).isEqualTo(LocalDate.of(2020, 1, 1));
    assertThat(result.basePremium()).isEqualByComparingTo("850.00");
    assertThat(result.territoryFactor()).isEqualByComparingTo("1.2600");
    assertThat(result.classFactor()).isEqualByComparingTo("1.0600");
  }

  @Test
  void failsClosedForMissingConfigurationAndInvalidPolicyType() {
    InMemoryRatingTableRepository repository = seededRepository();
    PremiumCalculator tables = calculator(repository, FactorMode.TABLES);
    repository.activeFactorSet = null;
    assertThatThrownBy(() -> tables.calculate(request("AUT")))
        .isInstanceOf(RatingConfigurationException.class)
        .hasMessageContaining("active rating factor set");

    repository.activeFactorSet = "LEGACY";
    repository.territoryFactors.clear();
    assertThatThrownBy(() -> tables.calculate(request("AUT")))
        .isInstanceOf(RatingConfigurationException.class)
        .hasMessageContaining("territory factor");

    repository.territoryFactors.add(factor("LEGACY", "*", "1.0000"));
    repository.classFactors.clear();
    assertThatThrownBy(() -> tables.calculate(request("AUT")))
        .isInstanceOf(RatingConfigurationException.class)
        .hasMessageContaining("class factor");

    repository.classFactors.add(classFactor("LEGACY", "*", "*", "1.0000"));
    repository.parameters.clear();
    assertThatThrownBy(() -> tables.calculate(request("AUT")))
        .isInstanceOf(RatingConfigurationException.class)
        .hasMessageContaining("rating parameters");
    assertThatThrownBy(() -> request("AUTX")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RatingRequest("AUT", null, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void breakdownValuesUseCobolPicScales() {
    PremiumBreakdown result =
        calculator(seededRepository(), FactorMode.LEGACY).calculate(request("AUT"));
    List<BigDecimal> twoDecimalValues =
        List.of(
            result.basePremium(),
            result.territoryPremium(),
            result.classPremium(),
            result.experiencePremium(),
            result.ratedPremium(),
            result.taxAmount(),
            result.surchargeAmount(),
            result.totalPremium());
    List<BigDecimal> fourDecimalValues =
        List.of(
            result.territoryFactor(),
            result.classFactor(),
            result.experienceMod(),
            result.scheduleMod(),
            result.taxRate(),
            result.premiumRecordBaseRate());

    assertThat(twoDecimalValues).allSatisfy(value -> assertThat(value.scale()).isEqualTo(2));
    assertThat(fourDecimalValues).allSatisfy(value -> assertThat(value.scale()).isEqualTo(4));
  }

  private static void assertPremium(
      PremiumCalculator calculator, String policyType, String total, String key) {
    PremiumBreakdown result = calculator.calculate(request(policyType));
    assertThat(result.totalPremium()).isEqualByComparingTo(total);
    assertThat(result.policyTypeKey()).isEqualTo(key);
    assertThat(result.policyType()).isEqualTo(policyType == null ? "" : policyType.stripTrailing());
  }

  private static RatingRequest request(String policyType) {
    return new RatingRequest(policyType, TODAY, null, null);
  }

  private static PremiumCalculator calculator(RatingTableRepository repository, FactorMode mode) {
    return new PremiumCalculator(repository, mode);
  }

  private static InMemoryRatingTableRepository seededRepository() {
    InMemoryRatingTableRepository repository = new InMemoryRatingTableRepository();
    repository.parameters.add(
        parameters("*", BASE_DATE, "1000.00", "1.0000", "1.0000", "0.0350", "25.00"));
    repository.parameters.add(
        parameters("AUT", BASE_DATE, "850.00", "1.0000", "1.0000", "0.0350", "25.00"));
    repository.parameters.add(
        parameters("HOM", BASE_DATE, "1200.00", "1.0000", "1.0000", "0.0350", "25.00"));
    repository.parameters.add(
        parameters("COM", BASE_DATE, "5000.00", "1.0000", "1.0000", "0.0350", "25.00"));
    repository.parameters.add(
        parameters("LIF", BASE_DATE, "400.00", "1.0000", "1.0000", "0.0350", "25.00"));
    repository.parameters.add(
        parameters("HLT", BASE_DATE, "3500.00", "1.0000", "1.0000", "0.0350", "25.00"));
    repository.territoryFactors.add(factor("LEGACY", "*", "1.0000"));
    repository.classFactors.add(classFactor("LEGACY", "*", "*", "1.0000"));
    return repository;
  }

  private static RatingParameters parameters(
      String type,
      LocalDate effectiveDate,
      String base,
      String exp,
      String schedule,
      String tax,
      String surcharge) {
    return new RatingParameters(
        type,
        effectiveDate,
        new BigDecimal(base),
        new BigDecimal(exp),
        new BigDecimal(schedule),
        new BigDecimal(tax),
        new BigDecimal(surcharge));
  }

  private static InMemoryRatingTableRepository.FactorRow factor(
      String set, String code, String value) {
    return new InMemoryRatingTableRepository.FactorRow(set, code, BASE_DATE, new BigDecimal(value));
  }

  private static InMemoryRatingTableRepository.ClassFactorRow classFactor(
      String set, String policyType, String classCode, String value) {
    return new InMemoryRatingTableRepository.ClassFactorRow(
        set, policyType, classCode, BASE_DATE, new BigDecimal(value));
  }
}
