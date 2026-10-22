package com.acme.insurance.pas.policy.renewal;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.lifecycle.LifecycleRejectedException;
import com.acme.insurance.pas.lifecycle.LifecycleRejectedException.Rejection;
import com.acme.insurance.pas.lifecycle.RenewalRules;
import com.acme.insurance.pas.lifecycle.RenewalSettings;
import com.acme.insurance.pas.policy.events.DocumentRequests;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.inquiry.PolicyInquiryRepository;
import com.acme.insurance.pas.policy.lifecycle.ClaimsHistoryClient;
import com.acme.insurance.pas.policy.lifecycle.LifecycleConflictException;
import com.acme.insurance.pas.policy.lifecycle.PolicyAuditRepository;
import com.acme.insurance.pas.policy.lifecycle.PolicyNotFoundException;
import com.acme.insurance.pas.policy.lifecycle.ShadowResultRepository;
import com.acme.insurance.pas.policy.repository.CoverageRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.RenewalPremium;
import com.acme.insurance.pas.rating.RenewalPremiumException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POLRNW on the new service. One transaction: the policy row lock, the policy and coverage writes,
 * the idempotency row, the audit row and the outbox records commit together or not at all (Q-16).
 * It publishes PolicyRenewed, UnderwritingReferralRequested with trigger RENEWAL (Q-17), and
 * DocumentRequested through the outbox.
 */
@Service
public class RenewalService {
  static final String PROGRAM = "POLRNW";

  private final PolicyInquiryRepository inquiry;
  private final PolicyRepository policies;
  private final CoverageRepository coverages;
  private final PolicyRenewalRepository renewals;
  private final PolicyAuditRepository audit;
  private final ShadowResultRepository shadow;
  private final ClaimsHistoryClient claims;
  private final RenewalProperties properties;
  private final Clock clock;
  private final OutboxWriter outbox;
  private final DocumentRequests documentRequests;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-managed collaborators are retained by design.")
  public RenewalService(
      PolicyInquiryRepository inquiry,
      PolicyRepository policies,
      CoverageRepository coverages,
      PolicyRenewalRepository renewals,
      PolicyAuditRepository audit,
      ShadowResultRepository shadow,
      ClaimsHistoryClient claims,
      RenewalProperties properties,
      Clock clock,
      OutboxWriter outbox,
      DocumentRequests documentRequests) {
    this.inquiry = inquiry;
    this.policies = policies;
    this.coverages = coverages;
    this.renewals = renewals;
    this.audit = audit;
    this.shadow = shadow;
    this.claims = claims;
    this.properties = properties;
    this.clock = clock;
    this.outbox = outbox;
    this.documentRequests = documentRequests;
  }

  @Transactional
  public RenewalResponse renew(
      String policyNumber,
      RenewalRequest request,
      CallerEntitlements caller,
      String actor,
      String correlationId) {
    Policy policy =
        inquiry.lockPolicy(policyNumber, caller).orElseThrow(PolicyNotFoundException::new);
    Calculation calculation = calculate(policy, request);
    Policy renewed =
        new Policy(
            policy.policyNumber(),
            policy.policyType(),
            Coded.of(PolicyStatus.ACTIVE),
            calculation.effectiveDate(),
            calculation.expiryDate(),
            policy.policyholderId(),
            policy.agentCode(),
            policy.branchCode(),
            calculation.premium().newPremium(),
            policy.deductible(),
            policy.coverageLimit(),
            policy.inceptionDate(),
            calculation.renewalCount(),
            Coded.of(UnderwritingStatus.PENDING),
            policy.riskScore(),
            policy.webIndicator(),
            policy.apiFlag(),
            policy.lastUpdated(),
            PROGRAM);
    long policyVersion = policies.saveLifecycle(renewed);
    coverages.redateActive(
        policy.policyNumber(), calculation.effectiveDate(), calculation.expiryDate());
    renewals.insert(
        new PolicyRenewalRepository.RenewalRecord(
            policy.policyNumber(),
            policy.expiryDate(),
            calculation.renewalCount(),
            policy.effectiveDate(),
            calculation.effectiveDate(),
            calculation.expiryDate(),
            calculation.premium().oldPremium(),
            calculation.premium().newPremium(),
            calculation.premium().rateChangePct(),
            calculation.openClaims(),
            actor));
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("priorExpiryDate", policy.expiryDate().toString());
    details.put("effectiveDate", calculation.effectiveDate().toString());
    details.put("expiryDate", calculation.expiryDate().toString());
    details.put("renewalCount", calculation.renewalCount());
    details.put("rateChangePct", calculation.premium().rateChangePct());
    details.put("capped", calculation.premium().capped());
    details.put("openClaims", calculation.openClaims());
    audit.record(
        policy.policyNumber(),
        PolicyAuditRepository.RENEWAL,
        policy.expiryDate().toString(),
        actor,
        calculation.premium().oldPremium(),
        calculation.premium().newPremium(),
        details);
    Map<String, Object> renewalData = new LinkedHashMap<>();
    renewalData.put("priorExpiryDate", policy.expiryDate());
    renewalData.put("effectiveDate", calculation.effectiveDate());
    renewalData.put("expiryDate", calculation.expiryDate());
    renewalData.put("renewalCount", calculation.renewalCount());
    renewalData.put("policyStatus", PolicyStatus.ACTIVE.code());
    renewalData.put("uwStatus", UnderwritingStatus.PENDING.code());
    renewalData.put("oldPremium", calculation.premium().oldPremium());
    renewalData.put("newPremium", calculation.premium().newPremium());
    renewalData.put("rateChangePct", calculation.premium().rateChangePct());
    renewalData.put("capped", calculation.premium().capped());
    UUID renewedEventId =
        outbox.append(
            OutboxWriter.POLICY_TOPIC,
            "PolicyRenewed",
            policy.policyNumber(),
            policyVersion,
            correlationId,
            renewalData);
    Map<String, Object> referral = new LinkedHashMap<>();
    referral.put("trigger", "RENEWAL");
    referral.put("decisionId", null);
    referral.put("decisionCode", null);
    referral.put("riskScore", null);
    referral.put("reasons", null);
    referral.put("lossRatio", null);
    referral.put("override", null);
    referral.put("overridesDecisionId", null);
    outbox.append(
        OutboxWriter.UNDERWRITING_TOPIC,
        "UnderwritingReferralRequested",
        policy.policyNumber(),
        policyVersion,
        correlationId,
        referral);
    documentRequests.append(
        policy.policyNumber(),
        policyVersion,
        correlationId,
        DocumentRequests.DocumentType.RENEWAL,
        renewedEventId);
    return response(policy, calculation, "APPLIED");
  }

  /** SHADOW route: compute the renewal and record the outcome in pas_shadow only. */
  @Transactional(noRollbackFor = LifecycleRejectedException.class)
  public RenewalResponse shadow(
      String policyNumber, RenewalRequest request, CallerEntitlements caller) {
    Policy policy =
        inquiry.findPolicy(policyNumber, caller).orElseThrow(PolicyNotFoundException::new);
    AtomicInteger openClaims = new AtomicInteger(-1);
    try {
      Calculation calculation = calculate(policy, request, openClaims);
      shadow.recordRenewal(
          new ShadowResultRepository.RenewalResult(
              policy.policyNumber(),
              request.termExpiryDate(),
              "RENEWED",
              calculation.effectiveDate(),
              calculation.expiryDate(),
              calculation.premium().newPremium(),
              calculation.premium().rateChangePct(),
              calculation.renewalCount(),
              calculation.openClaims()));
      return response(policy, calculation, "SHADOW");
    } catch (LifecycleRejectedException exception) {
      shadow.recordRenewal(
          new ShadowResultRepository.RenewalResult(
              policy.policyNumber(),
              request.termExpiryDate(),
              exception.rejection().name(),
              null,
              null,
              null,
              null,
              null,
              openClaims.get() < 0 ? null : openClaims.get()));
      throw exception;
    }
  }

  private Calculation calculate(Policy policy, RenewalRequest request) {
    return calculate(policy, request, new AtomicInteger(-1));
  }

  private Calculation calculate(Policy policy, RenewalRequest request, AtomicInteger openClaims) {
    if (renewals.exists(policy.policyNumber(), request.termExpiryDate())) {
      throw new LifecycleConflictException(
          "TERM_ALREADY_RENEWED", "The term ending " + request.termExpiryDate() + " is renewed");
    }
    if (!request.termExpiryDate().equals(policy.expiryDate())) {
      throw new LifecycleConflictException(
          "TERM_MISMATCH", "termExpiryDate does not match the policy's current term");
    }
    RenewalSettings settings = properties.toSettings();
    RenewalRules.checkEligibility(
        raw(policy.policyStatus()),
        raw(policy.uwStatus()),
        policy.expiryDate(),
        policy.renewalCount() == null ? 0 : policy.renewalCount(),
        LocalDate.now(clock),
        settings,
        () -> {
          openClaims.set(claims.countOpenClaims(policy.policyNumber()));
          return openClaims.get();
        });
    String state = policies.findPolicyholderState(policy.policyNumber()).orElse(null);
    RenewalPremium premium;
    try {
      premium =
          PremiumCalculator.renewalPremium(
              policy.totalPremium(), settings.upliftPct(), settings.capFor(state));
    } catch (RenewalPremiumException exception) {
      throw new LifecycleRejectedException(Rejection.RENEWAL_PREMIUM_INVALID);
    }
    return new Calculation(
        RenewalRules.renewalEffectiveDate(policy.expiryDate()),
        RenewalRules.renewalExpiryDate(policy.expiryDate()),
        RenewalRules.nextRenewalCount(policy.renewalCount() == null ? 0 : policy.renewalCount()),
        premium,
        openClaims.get());
  }

  private static RenewalResponse response(Policy prior, Calculation calculation, String mode) {
    return new RenewalResponse(
        prior.policyNumber(),
        mode,
        prior.effectiveDate(),
        prior.expiryDate(),
        calculation.effectiveDate(),
        calculation.expiryDate(),
        calculation.premium().oldPremium(),
        calculation.premium().newPremium(),
        calculation.premium().rateChangePct(),
        calculation.premium().capped(),
        calculation.renewalCount(),
        PolicyStatus.ACTIVE.code(),
        UnderwritingStatus.PENDING.code(),
        calculation.openClaims());
  }

  private static String raw(Coded<?> value) {
    return value == null ? null : value.raw();
  }

  private record Calculation(
      LocalDate effectiveDate,
      LocalDate expiryDate,
      int renewalCount,
      RenewalPremium premium,
      int openClaims) {}
}
