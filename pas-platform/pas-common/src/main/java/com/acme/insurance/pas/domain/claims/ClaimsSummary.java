package com.acme.insurance.pas.domain.claims;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ClaimsSummary(int claimCount, BigDecimal totalIncurred) {
  public ClaimsSummary {
    totalIncurred =
        totalIncurred == null ? null : totalIncurred.setScale(2, RoundingMode.UNNECESSARY);
  }
}
