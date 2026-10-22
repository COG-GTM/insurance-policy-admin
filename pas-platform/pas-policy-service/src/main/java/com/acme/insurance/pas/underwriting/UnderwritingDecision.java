package com.acme.insurance.pas.underwriting;

import java.math.BigDecimal;
import java.util.List;

public record UnderwritingDecision(
    Code code, int riskScore, List<String> reasons, BigDecimal lossRatio) {
  public UnderwritingDecision {
    reasons = List.copyOf(reasons);
  }

  public enum Code {
    AP,
    RS,
    RM,
    DC
  }
}
