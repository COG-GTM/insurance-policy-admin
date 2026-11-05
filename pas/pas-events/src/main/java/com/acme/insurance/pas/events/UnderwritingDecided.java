package com.acme.insurance.pas.events;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import java.time.LocalDate;

/**
 * {@code pas.underwriting.decided} v1, for automatic decisions and manual overrides. The risk score
 * is the full value (the 3-digit POLICY-RISK-SCORE truncated it, quirk Q-14). {@code referral} is
 * true for RS/RM, which the underwriting work queue picks up. The override reason is free text and
 * stays in the audit table.
 */
public record UnderwritingDecided(
    String policyNumber,
    LocalDate decisionDate,
    String decisionCode,
    Integer riskScore,
    String decisionReason,
    boolean referral,
    boolean override,
    String decidedBy) {

  public static UnderwritingDecided of(UnderwritingDecision d) {
    String code = d.decisionCode();
    return new UnderwritingDecided(
        d.policyNumber(),
        d.decisionDate(),
        code,
        d.riskScore(),
        d.decisionReason(),
        "RS".equals(code) || "RM".equals(code),
        d.overridden(),
        d.overridden() ? d.overrideBy() : d.underwriterId());
  }
}
