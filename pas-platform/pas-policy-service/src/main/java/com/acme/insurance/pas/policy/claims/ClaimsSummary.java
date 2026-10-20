package com.acme.insurance.pas.policy.claims;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Per-policy claims aggregates PAS needs; see {@link ClaimsHistoryPort} for the semantics. */
public record ClaimsSummary(long openClaimCount, long claimCount5y, BigDecimal incurredAmount5y) {

  public ClaimsSummary {
    if (openClaimCount < 0 || claimCount5y < 0) {
      throw new IllegalArgumentException("claim counts must not be negative");
    }
    Objects.requireNonNull(incurredAmount5y, "incurredAmount5y");
    incurredAmount5y = incurredAmount5y.setScale(2, RoundingMode.UNNECESSARY);
  }

  public static ClaimsSummary none() {
    return new ClaimsSummary(0, 0, BigDecimal.ZERO);
  }
}
