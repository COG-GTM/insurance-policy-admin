package com.acme.insurance.pas.policy.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record UnderwritingDecisionAudit(
    Long decisionId,
    String policyNumber,
    LocalDate decisionDate,
    String decisionCode,
    int riskScore,
    String decisionReason,
    List<String> reasons,
    BigDecimal lossRatio,
    String underwriterId,
    String decidedBy,
    OffsetDateTime createdTimestamp,
    Long overridesDecisionId,
    String overrideReason,
    String overrideBy) {
  public UnderwritingDecisionAudit {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
