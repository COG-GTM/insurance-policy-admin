package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.events.PasEventProperties;
import com.acme.insurance.pas.policy.events.PasEventTypes;
import com.acme.insurance.pas.policy.persistence.CoverageEntity;
import com.acme.insurance.pas.policy.persistence.CoverageRepository;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import com.acme.insurance.pas.policy.support.BusinessCalendar;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy renewal (replaces CICS transaction PRWL / program POLRNW). One transaction locks the
 * policy, checks eligibility, prices and re-dates the term, re-dates active coverages, writes the
 * audit row and records {@code PolicyRenewed} in the outbox. Any failure rolls all of it back
 * (quirk register Q-09 fix: POLRNW sent an error and a confirmation after a failed update).
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class RenewalService {

  public static final String PROGRAM = "POLRNW";
  static final String MSG_NOT_ELIGIBLE = "POLICY NOT ELIGIBLE FOR RENEWAL";
  static final String MSG_OPEN_CLAIMS = "POLICY HAS OPEN CLAIMS - NOT ELIGIBLE FOR RENEWAL";
  static final String MSG_TERM_INVALID = "POLICY TERM CANNOT BE RENEWED";

  private static final String ACTIVE = "AC";
  private static final String EXPIRED = "EX";
  private static final String UW_PENDING = "PN";

  private final PolicyRepository policies;
  private final CoverageRepository coverages;
  private final ClaimsHistoryPort claims;
  private final RenewalProperties properties;
  private final RenewalRateCapPolicy rateCap;
  private final PolicyAuditWriter audit;
  private final OutboxWriter outbox;
  private final PasEventProperties topics;
  private final BusinessCalendar calendar;

  public RenewalService(
      PolicyRepository policies,
      CoverageRepository coverages,
      ClaimsHistoryPort claims,
      RenewalProperties properties,
      RenewalRateCapPolicy rateCap,
      PolicyAuditWriter audit,
      OutboxWriter outbox,
      PasEventProperties topics,
      BusinessCalendar calendar) {
    this.policies = policies;
    this.coverages = coverages;
    this.claims = claims;
    this.properties = properties;
    this.rateCap = rateCap;
    this.audit = audit;
    this.outbox = outbox;
    this.topics = topics;
    this.calendar = calendar;
  }

  /** Empty when the policy does not exist or the caller is not entitled to it (404). */
  @Transactional
  public Optional<RenewalView> renew(
      String policyNumber, CallerEntitlements caller, String actor, String correlationId) {
    Optional<PolicyEntity> found = PolicyLocks.lockEntitled(policies, policyNumber, caller);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    PolicyEntity policy = found.get();
    String number = policy.getPolicyNumber().strip();
    Optional<String> ineligible = eligibilityMessage(policy.getPolicyStatus(), 0, 0);
    if (ineligible.isEmpty()) {
      long openClaims = claims.summarize(number, calendar.today()).openClaimCount();
      ineligible =
          eligibilityMessage(policy.getPolicyStatus(), openClaims, properties.maxOpenClaims());
    }
    if (ineligible.isPresent()) {
      throw new PolicyChangeRejectedException(
          PolicyChangeRejectedException.RENEWAL_NOT_ELIGIBLE, ineligible.get());
    }
    LocalDate oldEffective = policy.getEffectiveDate();
    LocalDate oldExpiry = policy.getExpiryDate();
    if (oldExpiry == null) {
      throw new PolicyChangeRejectedException(
          PolicyChangeRejectedException.RENEWAL_TERM_INVALID, MSG_TERM_INVALID);
    }
    BigDecimal oldPremium = zeroIfNull(policy.getTotalPremium());
    BigDecimal capPct = rateCap.capPct(policy);
    int renewalCount = policy.getRenewalCount() == null ? 0 : policy.getRenewalCount();
    RenewalCalculator.RenewalTerms terms =
        RenewalCalculator.calculate(
            oldPremium, properties.upliftPct(), capPct, YyyyMmDd.of(oldExpiry), renewalCount);
    LocalDate newExpiry = newExpiry(terms);

    policy.setTotalPremium(terms.newPremium());
    policy.setRenewalCount((short) terms.newRenewalCount());
    policy.setEffectiveDate(oldExpiry);
    policy.setExpiryDate(newExpiry);
    policy.setPolicyStatus(ACTIVE);
    policy.setUwStatus(UW_PENDING);
    policy.setLastUpdated(calendar.nowUtc());
    policy.setUpdatedBy(PROGRAM);
    PolicyEntity saved = policies.saveAndFlush(policy);

    List<CoverageEntity> active =
        coverages.findByIdPolicyNumberOrderByIdSequenceNumAsc(policy.getPolicyNumber()).stream()
            .filter(c -> ACTIVE.equals(strip(c.getStatus())))
            .toList();
    for (CoverageEntity coverage : active) {
      coverage.setEffectiveDate(oldExpiry);
      coverage.setExpiryDate(newExpiry);
    }
    coverages.saveAllAndFlush(active);

    long version = saved.getVersion();
    PolicyRenewedData data =
        new PolicyRenewedData(
            oldPremium,
            terms.newPremium(),
            terms.rateChangePct(),
            terms.capApplied(),
            oldEffective,
            oldExpiry,
            newExpiry,
            terms.newRenewalCount(),
            ACTIVE,
            UW_PENDING,
            strip(saved.getAgentCode()),
            strip(saved.getBranchCode()));
    audit.record(
        number,
        PolicyAuditWriter.ACTION_RENEWAL,
        PROGRAM,
        version,
        actor,
        correlationId,
        Map.of("coveragesRedated", active.size(), "change", data));
    outbox.append(
        topics.policyTopic(), PasEventTypes.POLICY_RENEWED, number, version, correlationId, data);
    return Optional.of(
        new RenewalView(
            number,
            oldPremium,
            terms.newPremium(),
            terms.rateChangePct(),
            capPct,
            terms.capApplied(),
            oldExpiry,
            newExpiry,
            terms.newRenewalCount(),
            ACTIVE,
            UW_PENDING,
            active.size(),
            version));
  }

  /**
   * POLRNW {@code 3000-CHECK-RENEWAL-ELIGIBILITY}: AC and EX policies only. POLRNW counted open
   * claims but never acted on the count; refusing more than {@code maxOpenClaims} is the Q-02 fix.
   * The claims port is only consulted for an eligible status.
   */
  static Optional<String> eligibilityMessage(
      String policyStatus, long openClaims, int maxOpenClaims) {
    String status = strip(policyStatus);
    if (!ACTIVE.equals(status) && !EXPIRED.equals(status)) {
      return Optional.of(MSG_NOT_ELIGIBLE);
    }
    return openClaims > maxOpenClaims ? Optional.of(MSG_OPEN_CLAIMS) : Optional.empty();
  }

  /**
   * POLRNW stores the new expiry in {@code PIC 9(8)} (Q-07); a 9999 sentinel expiry wraps to a
   * value that is not a date, which DB2's DATE column rejects too, so the renewal is refused.
   */
  private static LocalDate newExpiry(RenewalCalculator.RenewalTerms terms) {
    try {
      return YyyyMmDd.toDate(terms.newExpiryDate());
    } catch (DateTimeException e) {
      throw new PolicyChangeRejectedException(
          PolicyChangeRejectedException.RENEWAL_TERM_INVALID, MSG_TERM_INVALID);
    }
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  static String strip(String value) {
    return value == null ? "" : value.strip();
  }
}
