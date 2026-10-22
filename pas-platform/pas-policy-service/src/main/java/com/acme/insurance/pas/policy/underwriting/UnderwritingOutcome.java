package com.acme.insurance.pas.policy.underwriting;

import java.math.BigDecimal;
import java.util.List;

public record UnderwritingOutcome(
    Code code, int riskScore, List<String> reasons, BigDecimal lossRatio) {
  public UnderwritingOutcome {
    reasons = List.copyOf(reasons);
  }

  public enum Code {
    AP,
    RS,
    RM,
    DC
  }
}
