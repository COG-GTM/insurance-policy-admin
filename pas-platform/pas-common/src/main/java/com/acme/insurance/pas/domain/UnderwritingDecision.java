package com.acme.insurance.pas.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record UnderwritingDecision(
    String policyNumber,
    LocalDate decisionDate,
    Coded<UnderwritingDecisionCode> decisionCode,
    Integer riskScore,
    String decisionReason,
    String underwriterId,
    String overrideReason,
    String overrideBy,
    OffsetDateTime createdTimestamp) {
  public UnderwritingDecision {
    createdTimestamp = Scales.utc(createdTimestamp);
  }
}
