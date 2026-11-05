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
import com.acme.insurance.pas.underwriting.ClaimsSummary;
import com.acme.insurance.pas.underwriting.RiskDecision;
import com.acme.insurance.pas.underwriting.Underwriter;
import com.acme.insurance.pas.underwriting.UnderwritingOutcome;
import com.acme.insurance.pas.underwriting.UnderwritingOverride;
import com.acme.insurance.pas.underwriting.UnderwritingRules;
import com.acme.insurance.pas.underwriting.UnderwritingSubject;
import java.time.LocalDate;
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
 * interface is down nothing is written ({@code ClaimsHistoryUnavailableException}, 503). An
 * override needs the caller's own 8-character id from the token (403 otherwise); it is stored in
 * OVERRIDE_BY and POLICIES.UPDATED_BY (quirk Q-525-4).
 */
@Service
@EnableConfigurationProperties(UnderwritingProperties.class)
public class UnderwritingService {

  /** 403 message when the token carries no valid {@value CallerIdentity#LEGACY_USER_CLAIM}. */
  static final String OVERRIDE_NEEDS_USER =
      "OVERRIDE REQUIRES THE CALLER'S 8-CHARACTER USER ID ("
          + CallerIdentity.LEGACY_USER_CLAIM
          + ")";

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
    LocalDate today = clock.today();
    UnderwritingRules rules = properties.rules();
    ClaimsHistory history = claims;
    if (validPolicyNumber(policyNumber)) {
      // Entitlement first, so policies outside the caller's book are never locked, and the Claims
      // call before the lock, so the POLICIES row is not held during the HTTP round trip.
      Optional<UnderwritingSubject> visible = subject(policyNumber, entitlement);
      if (visible.isEmpty()) {
        return new UnderwritingResult.NotFound();
      }
      history = prefetched(visible.get().policyNumber(), today, rules);
      policies.lockPolicy(policyNumber);
    }
    Underwriter underwriter =
        new Underwriter(
            rules, pn -> subject(pn, entitlement), history, policies::activeCoverageLimitForBranch);
    UnderwritingOutcome outcome = underwriter.underwrite(policyNumber, today);
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

  /**
   * Reads the claims summary for the policy now and answers later calls for the same window from
   * it; any other request goes to the Claims interface.
   */
  private ClaimsHistory prefetched(String policyNumber, LocalDate today, UnderwritingRules rules) {
    LocalDate from = today.minusYears(rules.lossHistoryYears());
    ClaimsSummary summary = claims.summary(policyNumber, from, today);
    return (pn, f, t) ->
        policyNumber.equals(pn) && from.equals(f) && today.equals(t)
            ? summary
            : claims.summary(pn, f, t);
  }

  @Transactional
  public UnderwritingResult override(
      String policyNumber,
      String decisionCode,
      String reason,
      Entitlement entitlement,
      CallerIdentity caller,
      String correlationId) {
    if (!caller.legacyUserFromToken()) {
      return new UnderwritingResult.Forbidden(OVERRIDE_NEEDS_USER);
    }
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
        policyNumber, after.decisionCode(), after.riskScore(), after.overrideBy());
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
