package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class InMemoryRatingTableRepository implements RatingTableRepository {
  final List<RatingParameters> parameters = new ArrayList<>();
  final List<FactorRow> territoryFactors = new ArrayList<>();
  final List<ClassFactorRow> classFactors = new ArrayList<>();
  String activeFactorSet = "LEGACY";

  @Override
  public Optional<RatingParameters> findParameters(String policyType, LocalDate ratingDate) {
    return parameters.stream()
        .filter(row -> row.effectiveDate().compareTo(ratingDate) <= 0)
        .filter(row -> row.policyTypeKey().equals(policyType) || row.policyTypeKey().equals("*"))
        .sorted(
            Comparator.comparingInt(
                    (RatingParameters row) -> row.policyTypeKey().equals(policyType) ? 0 : 1)
                .thenComparing(RatingParameters::effectiveDate, Comparator.reverseOrder()))
        .findFirst();
  }

  @Override
  public Optional<String> findActiveFactorSet() {
    return Optional.ofNullable(activeFactorSet);
  }

  @Override
  public Optional<BigDecimal> findTerritoryFactor(
      String factorSet, String territoryCode, LocalDate ratingDate) {
    String code = territoryCode == null ? "*" : territoryCode;
    return territoryFactors.stream()
        .filter(row -> row.factorSet().equals(factorSet))
        .filter(row -> row.effectiveDate().compareTo(ratingDate) <= 0)
        .filter(row -> row.code().equals(code) || row.code().equals("*"))
        .sorted(
            Comparator.comparingInt((FactorRow row) -> row.code().equals(code) ? 0 : 1)
                .thenComparing(FactorRow::effectiveDate, Comparator.reverseOrder()))
        .map(FactorRow::factor)
        .findFirst();
  }

  @Override
  public Optional<BigDecimal> findClassFactor(
      String factorSet, String policyType, String classCode, LocalDate ratingDate) {
    String requestedClass = classCode == null ? "*" : classCode;
    return classFactors.stream()
        .filter(row -> row.factorSet().equals(factorSet))
        .filter(row -> row.effectiveDate().compareTo(ratingDate) <= 0)
        .filter(row -> row.policyType().equals(policyType) || row.policyType().equals("*"))
        .filter(row -> row.classCode().equals(requestedClass) || row.classCode().equals("*"))
        .sorted(
            Comparator.comparingInt(
                    (ClassFactorRow row) ->
                        (row.policyType().equals(policyType) ? 1 : 0)
                            + (row.classCode().equals(requestedClass) ? 1 : 0))
                .reversed()
                .thenComparingInt(row -> row.policyType().equals(policyType) ? 0 : 1)
                .thenComparingInt(row -> row.classCode().equals(requestedClass) ? 0 : 1)
                .thenComparing(ClassFactorRow::effectiveDate, Comparator.reverseOrder()))
        .map(ClassFactorRow::factor)
        .findFirst();
  }

  record FactorRow(String factorSet, String code, LocalDate effectiveDate, BigDecimal factor) {}

  record ClassFactorRow(
      String factorSet,
      String policyType,
      String classCode,
      LocalDate effectiveDate,
      BigDecimal factor) {}
}
