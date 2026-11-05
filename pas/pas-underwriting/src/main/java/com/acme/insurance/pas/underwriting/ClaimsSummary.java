package com.acme.insurance.pas.underwriting;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Claims history of one policy over the look-back window: what UNDWRT reads with {@code COUNT(*),
 * COALESCE(SUM(INCURRED_AMOUNT), 0)}.
 */
public record ClaimsSummary(int claimCount, BigDecimal totalIncurred) {

  public static final ClaimsSummary NONE = new ClaimsSummary(0, BigDecimal.ZERO);

  public ClaimsSummary {
    if (claimCount < 0) {
      throw new IllegalArgumentException("claimCount must not be negative");
    }
    Objects.requireNonNull(totalIncurred, "totalIncurred");
  }
}
