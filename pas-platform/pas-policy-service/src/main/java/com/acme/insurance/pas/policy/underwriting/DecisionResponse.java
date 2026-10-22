package com.acme.insurance.pas.policy.underwriting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record DecisionResponse(
    Long decisionId,
    String policyNumber,
    LocalDate decisionDate,
    String decisionCode,
    int riskScore,
    List<String> reasons,
    BigDecimal lossRatio,
    String underwriterId,
    String decidedBy,
    OffsetDateTime createdTimestamp,
    Long overridesDecisionId,
    String overrideReason,
    boolean shadow) {
  public DecisionResponse {
    reasons = List.copyOf(reasons);
  }
}
