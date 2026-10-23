package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ClassFactor(
    String factorSet,
    String policyType,
    String classCode,
    LocalDate effectiveDate,
    BigDecimal ratingFactor,
    String description) {
  public ClassFactor {
    ratingFactor = Scales.normalize(ratingFactor, 4);
  }
}
