package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface RatingTableRepository {
  Optional<RatingParameters> findParameters(String policyType, LocalDate ratingDate);

  Optional<String> findActiveFactorSet();

  Optional<BigDecimal> findTerritoryFactor(
      String factorSet, String territoryCode, LocalDate ratingDate);

  Optional<BigDecimal> findClassFactor(
      String factorSet, String policyType, String classCode, LocalDate ratingDate);
}
