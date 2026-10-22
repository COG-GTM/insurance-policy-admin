package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TerritoryFactor(
    String factorSet, String territoryCode, LocalDate effectiveDate, BigDecimal ratingFactor) {
  public TerritoryFactor {
    ratingFactor = Scales.normalize(ratingFactor, 4);
  }
}
