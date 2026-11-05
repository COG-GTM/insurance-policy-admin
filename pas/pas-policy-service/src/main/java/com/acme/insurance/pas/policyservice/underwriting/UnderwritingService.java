package com.acme.insurance.pas.policyservice.underwriting;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.acme.insurance.pas.events.PasEvents;
import com.acme.insurance.pas.persistence.PolicyHolderJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.persistence.UnderwritingDecisionJdbcRepository;
import com.acme.insurance.pas.policyservice.audit.AuditWriter;
import com.acme.insurance.pas.policyservice.config.BusinessClock;
import com.acme.insurance.pas.policyservice.events.OutboxWriter;
import com.acme.insurance.pas.policyservice.security.CallerIdentity;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import com.acme.insurance.pas.underwriting.ClaimsHistory;
import com.acme.insurance.pas.underwriting.RiskDecision;
import com.acme.insurance.pas.underwriting.Underwriter;
import com.acme.insurance.pas.underwriting.UnderwritingOutcome;
import com.acme.insurance.pas.underwriting.UnderwritingOverride;
import com.acme.insurance.pas.underwriting.UnderwritingSubject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Underwriting decisions (replaces CICS PUWR / UNDWRT) and manual overrides. Each request is one
 * transaction: the decision row, the POLICIES update, the audit row and the decision event in the
 * outbox. A policy outside the caller's entitlement is reported as not found. When the claims
 * interface is down nothing is written ({@code ClaimsHistoryUnavailableException}, 503).
 */
@Service
@EnableConfigurationProperties(UnderwritingProperties.class)
public class UnderwritingService {

  private final PolicyJdbcRepository policies;
  private final PolicyHolderJdbcRepository holders;
  private final UnderwritingDecisionJdbcRepository decisions;
  private final ClaimsHistory claims;
  private final OutboxWriter outbox;
  private final AuditWriter audit;
  private final BusinessClock clock;
  private final UnderwritingProperties properties;
  private final PasEvents events;

  public UnderwritingService(
      PolicyJdbcRepository policies,
      PolicyHolderJdbcRepository holders,
      UnderwritingDecisionJdbcRepository decisions,
      ClaimsHistory claims,
      OutboxWriter outbox,
      AuditWriter audit,
      BusinessClock clock,
      UnderwritingProperties properties) {
    this.policies = policies;
    this.holders = holders;
    this.decisions = decisions;
    this.claims = claims;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
    this.properties = properties;
    this.events = new PasEvents(clock.clock());
  }

  @Transactional
  public UnderwritingResult decide(
      String policyNumber, Entitlement entitlement, CallerIdentity caller, String correlationId) {
    Underwriter underwriter =
        new Underwriter(
            properties.rules(),
            pn -> subject(pn, entitlement),
            claims,
            policies::activeCoverageLimitForBranch);
    if (validPolicyNumber(policyNumber)) {
      policies.lockPolicy(policyNumber);
    }
    UnderwritingOutcome outcome = underwriter.underwrite(policyNumber, clock.today());
    if (outcome instanceof UnderwritingOutcome.Rejected rejected) {
      return rejected.reason() == UnderwritingOutcome.Rejection.NOT_FOUND
          ? new UnderwritingResult.NotFound()
          : new UnderwritingResult.Invalid(rejected.reason().legacyMessage(), List.of());
    }
    RiskDecision decision = ((UnderwritingOutcome.Decided) outcome).decision();
    Policy before = policies.findPolicy(decision.policyNumber()).orElseThrow();
    UnderwritingDecision row = decision.toDecisionRow();
    decisions.insert(row);
    policies.updateUnderwriting(
        decision.policyNumber(),
        decision.decisionCode(),
        decision.riskScore(),
        RiskDecision.UPDATED_BY);
    audit.record(
        AuditWriter.UNDERWRITING_DECIDED,
        decision.policyNumber(),
        caller,
        correlationId,
        uw(before.uwStatusCode(), before.riskScore()),
        decisionDetails(decision));
    outbox.append(events.underwritingDecided(row, correlationId));
    return new UnderwritingResult.Done(row, reasons(decision));
  }

  @Transactional
  public UnderwritingResult override(
      String policyNumber,
      String decisionCode,
      String reason,
      Entitlement entitlement,
      CallerIdentity caller,
      String correlationId) {
    UnderwritingOverride override =
        new UnderwritingOverride(decisionCode, reason, caller.legacyUser());
    List<String> violations = override.violations();
    if (!violations.isEmpty()) {
      return new UnderwritingResult.Invalid("INVALID OVERRIDE", violations);
    }
    Optional<Policy> policy = visiblePolicy(policyNumber, entitlement);
    if (policy.isEmpty()) {
      return new UnderwritingResult.NotFound();
    }
    policies.lockPolicy(policyNumber);
    Optional<UnderwritingDecision> latest = decisions.findLatest(policyNumber);
    if (latest.isEmpty()) {
      return new UnderwritingResult.Conflict("NO UNDERWRITING DECISION TO OVERRIDE");
    }
    UnderwritingDecision before = latest.get();
    UnderwritingDecision after = override.applyTo(before);
    if (decisions.applyOverride(before, after) != 1) {
      return new UnderwritingResult.Conflict("UNDERWRITING DECISION CHANGED, RETRY");
    }
    policies.updateUnderwriting(
        policyNumber, after.decisionCode(), after.riskScore(), RiskDecision.UPDATED_BY);
    Map<String, Object> afterDetails = uw(after.decisionCode(), after.riskScore());
    afterDetails.put("overrideReason", after.overrideReason());
    afterDetails.put("overrideBy", after.overrideBy());
    audit.record(
        AuditWriter.UNDERWRITING_OVERRIDDEN,
        policyNumber,
        caller,
        correlationId,
        uw(before.decisionCode(), before.riskScore()),
        afterDetails);
    outbox.append(events.underwritingDecided(after, correlationId));
    return new UnderwritingResult.Done(after, List.of(after.decisionReason()));
  }

  @Transactional(readOnly = true)
  public Optional<List<UnderwritingDecision>> decisions(
      String policyNumber, Entitlement entitlement) {
    return visiblePolicy(policyNumber, entitlement)
        .map(p -> decisions.findByPolicy(p.policyNumber()));
  }

  private static boolean validPolicyNumber(String policyNumber) {
    return policyNumber != null && !policyNumber.isBlank() && policyNumber.length() <= 12;
  }

  private Optional<Policy> visiblePolicy(String policyNumber, Entitlement entitlement) {
    if (!validPolicyNumber(policyNumber)) {
      return Optional.empty();
    }
    return policies
        .findPolicy(policyNumber)
        .filter(p -> entitlement.permits(p.agentCode(), p.branchCode()));
  }

  private Optional<UnderwritingSubject> subject(String policyNumber, Entitlement entitlement) {
    return visiblePolicy(policyNumber, entitlement)
        .flatMap(
            p ->
                p.policyholderId() == null
                    ? Optional.empty()
                    : holders.find(p.policyholderId()).map(h -> subject(p, h)));
  }

  private static UnderwritingSubject subject(Policy p, PolicyHolder h) {
    return new UnderwritingSubject(
        p.policyNumber(),
        p.policyTypeCode(),
        p.totalPremium(),
        p.coverageLimit(),
        p.branchCode(),
        p.agentCode(),
        p.policyholderId(),
        h.custTypeCode(),
        h.creditScore(),
        h.riskTierCode());
  }

  private static Map<String, Object> uw(String status, Integer score) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("uwStatus", status);
    m.put("riskScore", score);
    return m;
  }

  private static Map<String, Object> decisionDetails(RiskDecision d) {
    Map<String, Object> m = uw(d.decisionCode(), d.riskScore());
    m.put("decisionReason", d.reason());
    m.put("claimCount", d.assessment().claimCount());
    m.put("lossRatio", d.lossRatio());
    m.put("accumulationExceeded", d.assessment().accumulationExceeded());
    return m;
  }

  private static List<String> reasons(RiskDecision d) {
    List<String> reasons = new ArrayList<>();
    reasons.add(d.band().reason());
    if (d.assessment().accumulationExceeded()) {
      reasons.add("ACCUMULATION LIMIT EXCEEDED FOR BRANCH");
    }
    return reasons;
  }
}
