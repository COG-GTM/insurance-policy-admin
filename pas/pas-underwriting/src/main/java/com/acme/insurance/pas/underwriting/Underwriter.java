package com.acme.insurance.pas.underwriting;

import java.time.LocalDate;
import java.util.Objects;

/**
 * UNDWRT (CICS PUWR) without the terminal: reads the subject, the claims history and the branch
 * exposure through the ports, scores the risk and renders the decision. The caller writes the
 * decision row, the policy update, the audit record and the decision event in one transaction.
 *
 * <p>The decision reason is the band text; when the branch accumulation limit was exceeded the
 * accumulation reason is kept after it instead of being overwritten (quirk Q-16).
 */
public final class Underwriter {

  /** POLICIES.POLICY_NUMBER is CHAR(12). */
  static final int POLICY_NUMBER_LENGTH = 12;

  private final UnderwritingRules rules;
  private final RiskScorer scorer;
  private final UnderwritingSubjects subjects;
  private final ClaimsHistory claims;
  private final BranchExposure exposure;

  public Underwriter(
      UnderwritingRules rules,
      UnderwritingSubjects subjects,
      ClaimsHistory claims,
      BranchExposure exposure) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.scorer = new RiskScorer(rules);
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.claims = Objects.requireNonNull(claims, "claims");
    this.exposure = Objects.requireNonNull(exposure, "exposure");
  }

  /**
   * Decides on {@code policyNumber} as of {@code businessDate}.
   *
   * @throws ClaimsHistoryUnavailableException when the claims interface does not answer
   */
  public UnderwritingOutcome underwrite(String policyNumber, LocalDate businessDate) {
    Objects.requireNonNull(businessDate, "businessDate");
    if (policyNumber == null
        || policyNumber.isBlank()
        || policyNumber.length() > POLICY_NUMBER_LENGTH) {
      return new UnderwritingOutcome.Rejected(UnderwritingOutcome.Rejection.INVALID_REQUEST);
    }
    var subject = subjects.find(policyNumber);
    if (subject.isEmpty()) {
      return new UnderwritingOutcome.Rejected(UnderwritingOutcome.Rejection.NOT_FOUND);
    }
    UnderwritingSubject s = subject.get();
    ClaimsSummary history =
        claims.summary(
            s.policyNumber(), businessDate.minusYears(rules.lossHistoryYears()), businessDate);
    if (history == null) {
      throw new ClaimsHistoryUnavailableException("claims interface returned no summary");
    }
    var branchLimit = s.branchCode() == null ? null : exposure.activeCoverageLimit(s.branchCode());
    RiskAssessment assessment = scorer.assess(s, history, branchLimit);
    DecisionBand band = rules.band(assessment.riskScore());
    return new UnderwritingOutcome.Decided(
        new RiskDecision(
            s.policyNumber(), businessDate, band, assessment, reason(band, assessment, s)));
  }

  static String reason(DecisionBand band, RiskAssessment assessment, UnderwritingSubject s) {
    String reason = band.reason();
    if (assessment.accumulationExceeded()) {
      reason += "; ACCUMULATION LIMIT EXCEEDED FOR BRANCH " + s.branchCode();
    }
    return reason.length() > RiskDecision.REASON_LENGTH
        ? reason.substring(0, RiskDecision.REASON_LENGTH)
        : reason;
  }
}
