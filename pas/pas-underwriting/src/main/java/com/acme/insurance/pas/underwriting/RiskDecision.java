package com.acme.insurance.pas.underwriting;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An automatic underwriting decision and the writes UNDWRT {@code 6000-WRITE-DECISION} makes for
 * it: one UNDERWRITING_DECISIONS row ({@link #toDecisionRow()}) and the POLICIES update of
 * UW_STATUS and RISK_SCORE by {@link #UPDATED_BY}.
 */
public record RiskDecision(
    String policyNumber,
    LocalDate decisionDate,
    DecisionBand band,
    RiskAssessment assessment,
    String reason) {

  /** UNDERWRITING_DECISIONS.UNDERWRITER_ID of an automatic decision. */
  public static final String UNDERWRITER_ID = "SYSTEM";

  /** POLICIES.UPDATED_BY written with the decision (8-character service id, Q-518-1). */
  public static final String UPDATED_BY = "UNDWRT";

  /** DECISION_REASON is VARCHAR(100). */
  static final int REASON_LENGTH = 100;

  public String decisionCode() {
    return band.status().code();
  }

  public int riskScore() {
    return assessment.riskScore();
  }

  public BigDecimal lossRatio() {
    return assessment.lossRatio();
  }

  public UnderwritingDecision toDecisionRow() {
    return new UnderwritingDecision(
        null,
        policyNumber,
        decisionDate,
        decisionCode(),
        riskScore(),
        reason,
        UNDERWRITER_ID,
        null,
        null,
        null);
  }
}
