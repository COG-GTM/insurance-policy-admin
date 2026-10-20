package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.policy.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.policy.claims.ClaimsSummary;
import com.acme.insurance.pas.policy.events.PasEventTypes;
import com.acme.insurance.pas.policy.events.PolicyEventPublisher;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.persistence.UnderwritingDecisionEntity;
import com.acme.insurance.pas.policy.persistence.UnderwritingDecisionRepository;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import com.acme.insurance.pas.policy.support.BusinessCalendar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Underwriting decisions (replaces CICS PUWR / UNDWRT). Every decision, automated or manual, is a
 * new {@code pas.underwriting_decisions} row (same-day decisions are kept, quirk register Q-20),
 * the policy's {@code UW_STATUS} is updated and an event is written to the outbox. A policy the
 * caller is not entitled to is reported like a missing one ({@link Optional#empty()} → 404).
 */
@Service
public class UnderwritingService {

  /** {@code UNDERWRITER_ID} for automated decisions (UNDWRT literal). */
  public static final String SYSTEM_UNDERWRITER = "SYSTEM";

  /** {@code UPDATED_BY} written by an automated decision (UNDWRT literal). */
  public static final String UPDATED_BY_UNDWRT = "UNDWRT";

  /** {@code UPDATED_BY} for a manual override (CHAR(8)). */
  public static final String UPDATED_BY_OVERRIDE = "UWOVRIDE";

  public static final String OVERRIDE_REASON_TEXT = "MANUAL OVERRIDE";

  private final PolicyRepository policies;
  private final PolicyHolderRepository holders;
  private final UnderwritingDecisionRepository decisions;
  private final ClaimsHistoryPort claims;
  private final PolicyEventPublisher events;
  private final BusinessCalendar calendar;

  public UnderwritingService(
      PolicyRepository policies,
      PolicyHolderRepository holders,
      UnderwritingDecisionRepository decisions,
      ClaimsHistoryPort claims,
      PolicyEventPublisher events,
      BusinessCalendar calendar) {
    this.policies = policies;
    this.holders = holders;
    this.decisions = decisions;
    this.claims = claims;
    this.events = events;
    this.calendar = calendar;
  }

  /** Automated UNDWRT decision. */
  @Transactional
  public Optional<UnderwritingDecisionView> decide(
      String policyNumber, CallerEntitlements caller, String correlationId) {
    Optional<PolicyEntity> found = entitledPolicy(policyNumber, caller);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    PolicyEntity policy = found.get();
    PolicyHolderEntity holder =
        holders
            .findById(policy.getPolicyholderId())
            .orElseThrow(UnderwritingDataIncompleteException::new);
    if (policy.getTotalPremium() == null
        || policy.getCoverageLimit() == null
        || policy.getBranchCode() == null
        || holder.getCreditScore() == null
        || holder.getRiskTier() == null) {
      throw new UnderwritingDataIncompleteException();
    }
    LocalDate today = calendar.today();
    ClaimsSummary history = claims.summarize(policy.getPolicyNumber().strip(), today);
    BigDecimal accumulation = policies.sumActiveCoverageLimitByBranch(policy.getBranchCode());
    RiskAssessment assessment =
        UnderwritingRiskScorer.assess(
            new RiskInputs(
                policy.getTotalPremium(),
                policy.getCoverageLimit(),
                policy.getBranchCode(),
                holder.getCreditScore(),
                holder.getRiskTier(),
                history.claimCount5y(),
                history.incurredAmount5y(),
                accumulation == null ? BigDecimal.ZERO : accumulation));

    UnderwritingDecisionEntity decision = new UnderwritingDecisionEntity();
    decision.setPolicyNumber(policy.getPolicyNumber());
    decision.setDecisionDate(today);
    decision.setDecisionCode(assessment.decision().code());
    decision.setRiskScore((short) assessment.riskScore());
    decision.setDecisionReason(assessment.decisionReason());
    decision.setUnderwriterId(SYSTEM_UNDERWRITER);
    decision.setCreatedTimestamp(calendar.nowUtc());
    decision = decisions.saveAndFlush(decision);

    policy.setUwStatus(assessment.decision().code());
    policy.setRiskScore((short) assessment.riskScore());
    policy.setLastUpdated(calendar.nowUtc());
    policy.setUpdatedBy(UPDATED_BY_UNDWRT);
    policy = policies.saveAndFlush(policy);

    publish(policy, decision, assessment.decision(), assessment.policyRiskScore(), correlationId);
    return Optional.of(UnderwritingDecisionView.from(decision));
  }

  /** Manual override by {@code subject} of the latest decision; keeps the latest risk score. */
  @Transactional
  public Optional<UnderwritingDecisionView> override(
      String policyNumber,
      CallerEntitlements caller,
      String subject,
      OverrideRequest request,
      String correlationId) {
    Optional<PolicyEntity> found = entitledPolicy(policyNumber, caller);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    PolicyEntity policy = found.get();
    List<UnderwritingDecisionEntity> history =
        decisions.findByPolicyNumberOrderByDecisionDateAscDecisionIdAsc(policy.getPolicyNumber());
    if (history.isEmpty()) {
      throw new NoDecisionToOverrideException();
    }
    UnderwritingDecisionEntity latest = history.get(history.size() - 1);
    UnderwritingDecisionCode code =
        UnderwritingDecisionCode.fromCode(request.decisionCode())
            .orElseThrow(() -> new IllegalArgumentException("unknown decision code"));

    UnderwritingDecisionEntity decision = new UnderwritingDecisionEntity();
    decision.setPolicyNumber(policy.getPolicyNumber());
    decision.setDecisionDate(calendar.today());
    decision.setDecisionCode(code.code());
    decision.setRiskScore(latest.getRiskScore());
    decision.setDecisionReason(OVERRIDE_REASON_TEXT);
    decision.setUnderwriterId(subject);
    decision.setOverrideReason(request.reason().strip());
    decision.setOverrideBy(subject);
    decision.setCreatedTimestamp(calendar.nowUtc());
    decision = decisions.saveAndFlush(decision);

    policy.setUwStatus(code.code());
    policy.setLastUpdated(calendar.nowUtc());
    policy.setUpdatedBy(UPDATED_BY_OVERRIDE);
    policy = policies.saveAndFlush(policy);

    Integer policyRiskScore =
        latest.getRiskScore() == null ? null : latest.getRiskScore() % POLICY_SCORE_MODULUS;
    publish(policy, decision, code, policyRiskScore, correlationId);
    return Optional.of(UnderwritingDecisionView.from(decision));
  }

  /** The policy's decisions, oldest first. */
  @Transactional(readOnly = true)
  public Optional<List<UnderwritingDecisionView>> history(
      String policyNumber, CallerEntitlements caller) {
    return entitledPolicy(policyNumber, caller)
        .map(
            p ->
                decisions
                    .findByPolicyNumberOrderByDecisionDateAscDecisionIdAsc(p.getPolicyNumber())
                    .stream()
                    .map(UnderwritingDecisionView::from)
                    .toList());
  }

  private static final int POLICY_SCORE_MODULUS = 1000;

  private void publish(
      PolicyEntity policy,
      UnderwritingDecisionEntity decision,
      UnderwritingDecisionCode code,
      Integer policyRiskScore,
      String correlationId) {
    events.underwritingDecision(
        policy,
        code.isReferral()
            ? PasEventTypes.UNDERWRITING_REFERRED
            : PasEventTypes.UNDERWRITING_DECIDED,
        new UnderwritingDecisionData(
            decision.getDecisionId(),
            decision.getDecisionDate(),
            code.code(),
            decision.getRiskScore() == null ? null : decision.getRiskScore().intValue(),
            policyRiskScore,
            decision.getDecisionReason(),
            decision.getUnderwriterId(),
            decision.getOverrideBy() != null,
            decision.getOverrideBy()),
        correlationId);
  }

  private Optional<PolicyEntity> entitledPolicy(String policyNumber, CallerEntitlements caller) {
    if (policyNumber == null || policyNumber.isBlank()) {
      return Optional.empty();
    }
    return policies
        .findById(policyNumber)
        .filter(p -> caller.canAccess(p.getAgentCode(), p.getBranchCode()));
  }
}
