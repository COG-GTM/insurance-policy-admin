package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ACMEINS.UNDERWRITING_DECISIONS row. {@code decisionId} is the PostgreSQL key added by V13 (quirk
 * Q-18) and is null for a row not yet stored or read from DB2. The audit-user columns hold 8
 * characters (quirk Q-518-1).
 */
public record UnderwritingDecision(
    Long decisionId,
    String policyNumber,
    LocalDate decisionDate,
    String decisionCode,
    Integer riskScore,
    String decisionReason,
    String underwriterId,
    String overrideReason,
    String overrideBy,
    LocalDateTime createdTimestamp) {

  public UnderwritingDecision {
    text(required(policyNumber, "policyNumber"), 12, "policyNumber");
    required(decisionDate, "decisionDate");
    text(required(decisionCode, "decisionCode"), 2, "decisionCode");
    Constraints.range(riskScore, Short.MAX_VALUE, "riskScore");
    text(decisionReason, 100, "decisionReason");
    text(underwriterId, 8, "underwriterId");
    text(overrideReason, 200, "overrideReason");
    text(overrideBy, 8, "overrideBy");
  }

  public Optional<UnderwritingStatus> decision() {
    return UnderwritingStatus.fromCode(decisionCode);
  }

  public boolean overridden() {
    return overrideBy != null;
  }
}
