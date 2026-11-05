package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A policy term from EFFECTIVE_DATE to EXPIRY_DATE (POLEND 3000-CALCULATE-PRORATA). Day counts are
 * real calendar days, not POLEND's unsigned PIC 9(03) fields (Q-20).
 */
public record PolicyTerm(LocalDate effectiveDate, LocalDate expiryDate) {

  public PolicyTerm {
    Objects.requireNonNull(effectiveDate, "effectiveDate");
    Objects.requireNonNull(expiryDate, "expiryDate");
    if (expiryDate.isBefore(effectiveDate)) {
      throw new IllegalArgumentException(
          "policy term ends before it starts: " + effectiveDate + " to " + expiryDate);
    }
  }

  public long days() {
    return ChronoUnit.DAYS.between(effectiveDate, expiryDate);
  }

  /** Days from {@code on} to expiry, never below 0 (after expiry) or above the term (before it). */
  public long daysRemaining(LocalDate on) {
    return Math.max(0, Math.min(days(), ChronoUnit.DAYS.between(on, expiryDate)));
  }

  /** daysRemaining / days, truncated to {@code scale}; 1 for a zero-length term, as POLEND. */
  public BigDecimal remainingShare(LocalDate on, int scale) {
    long days = days();
    if (days == 0) {
      return BigDecimal.ONE.setScale(scale);
    }
    return BigDecimal.valueOf(daysRemaining(on))
        .divide(BigDecimal.valueOf(days), scale, RoundingMode.DOWN);
  }
}
