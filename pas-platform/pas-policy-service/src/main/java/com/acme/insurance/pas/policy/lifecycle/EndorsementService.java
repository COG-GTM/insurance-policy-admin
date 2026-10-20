package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.api.InvalidRequestException;
import com.acme.insurance.pas.policy.events.PolicyEventPublisher;
import com.acme.insurance.pas.policy.persistence.EndorsementEntity;
import com.acme.insurance.pas.policy.persistence.EndorsementId;
import com.acme.insurance.pas.policy.persistence.EndorsementRepository;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import com.acme.insurance.pas.policy.support.BusinessCalendar;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mid-term endorsement (replaces CICS transaction PEND / program POLEND). Like POLEND it locks the
 * policy row ({@code SELECT ... FOR UPDATE} for {@code KEEP UPDATE LOCKS}), validates, computes the
 * pro-rata factor over the days remaining, inserts the ENDORSEMENTS row and applies the premium
 * adjustment to POLICIES. The audit row replaces the ENDORSEMENT-AUDIT TS queue and {@code
 * PolicyEndorsed} goes to the outbox, all in one transaction (quirk register Q-14 fix: POLEND
 * carried on and confirmed after a failed insert).
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class EndorsementService {

  public static final String PROGRAM = "POLEND";
  static final String MSG_NOT_ACTIVE = "ONLY ACTIVE POLICIES CAN BE ENDORSED";
  static final String MSG_TYPE_REQUIRED = "ENDORSEMENT TYPE IS REQUIRED";
  static final String MSG_TERM_MISSING = "POLICY TERM DATES ARE MISSING";

  /** POLEND {@code WS-ENDORSEMENT-TYPE} 88-levels. */
  static final Set<String> TYPES = Set.of("CAD", "CRM", "LCH", "ACH", "CAN");

  private static final String ACTIVE = "AC";

  private final PolicyRepository policies;
  private final EndorsementRepository endorsements;
  private final PolicyAuditWriter audit;
  private final PolicyEventPublisher events;
  private final BusinessCalendar calendar;

  public EndorsementService(
      PolicyRepository policies,
      EndorsementRepository endorsements,
      PolicyAuditWriter audit,
      PolicyEventPublisher events,
      BusinessCalendar calendar) {
    this.policies = policies;
    this.endorsements = endorsements;
    this.audit = audit;
    this.events = events;
    this.calendar = calendar;
  }

  /** Empty when the policy does not exist or the caller is not entitled to it (404). */
  @Transactional
  public Optional<EndorsementView> endorse(
      String policyNumber,
      EndorsementRequest request,
      CallerEntitlements caller,
      String actor,
      String correlationId) {
    Optional<PolicyEntity> found = PolicyLocks.lockEntitled(policies, policyNumber, caller);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    PolicyEntity policy = found.get();
    String type = request == null ? "" : RenewalService.strip(request.endorsementType());
    validate(policy, type);
    LocalDate today = calendar.today();
    if (policy.getEffectiveDate() == null || policy.getExpiryDate() == null) {
      throw new PolicyChangeRejectedException(
          PolicyChangeRejectedException.ENDORSEMENT_INVALID, MSG_TERM_MISSING);
    }
    EndorsementCalculator.ProRata proRata =
        EndorsementCalculator.proRata(
            YyyyMmDd.of(policy.getEffectiveDate()),
            YyyyMmDd.of(policy.getExpiryDate()),
            YyyyMmDd.of(today));
    BigDecimal adjustment = EndorsementCalculator.premiumAdjustment(proRata);
    String number = policy.getPolicyNumber().strip();
    int seq =
        EndorsementCalculator.nextSequence(endorsements.maxSequence(policy.getPolicyNumber()));
    LocalDateTime now = calendar.nowUtc();

    EndorsementEntity endorsement = new EndorsementEntity();
    endorsement.setId(new EndorsementId(policy.getPolicyNumber(), seq));
    endorsement.setEndorsementType(type);
    endorsement.setEffectiveDate(today);
    endorsement.setPremiumAdjustment(adjustment);
    endorsement.setProcessedDate(now);
    endorsement.setProcessedBy(PROGRAM);
    endorsements.saveAndFlush(endorsement);

    BigDecimal total = EndorsementCalculator.adjustedPremium(policy.getTotalPremium(), adjustment);
    policy.setTotalPremium(total);
    policy.setLastUpdated(now);
    policy.setUpdatedBy(PROGRAM);
    PolicyEntity saved = policies.saveAndFlush(policy);
    long version = saved.getVersion();

    PolicyEndorsedData data =
        new PolicyEndorsedData(
            seq,
            type,
            today,
            proRata.daysInTerm(),
            proRata.daysRemaining(),
            proRata.factor(),
            adjustment,
            total,
            RenewalService.strip(saved.getAgentCode()),
            RenewalService.strip(saved.getBranchCode()));
    audit.record(
        number,
        PolicyAuditWriter.ACTION_ENDORSEMENT,
        PROGRAM,
        version,
        actor,
        correlationId,
        Map.of("change", data));
    events.policyEndorsed(saved, data, correlationId);
    return Optional.of(
        new EndorsementView(
            number,
            seq,
            type,
            today,
            proRata.daysInTerm(),
            proRata.daysRemaining(),
            proRata.factor(),
            adjustment,
            total,
            version));
  }

  private static void validate(PolicyEntity policy, String type) {
    Optional<String> message = validationMessage(policy.getPolicyStatus(), type);
    if (message.isPresent()) {
      throw new PolicyChangeRejectedException(
          PolicyChangeRejectedException.ENDORSEMENT_INVALID, message.get());
    }
    if (!TYPES.contains(type)) {
      throw new InvalidRequestException("endorsementType must be one of " + sorted());
    }
  }

  /**
   * POLEND {@code 3000-VALIDATE-ENDORSEMENT}: both checks run and the last failing one sets the
   * message, so a blank type on an inactive policy reports the type (Q-10).
   */
  static Optional<String> validationMessage(String policyStatus, String type) {
    String message = null;
    if (!ACTIVE.equals(RenewalService.strip(policyStatus))) {
      message = MSG_NOT_ACTIVE;
    }
    if (RenewalService.strip(type).isEmpty()) {
      message = MSG_TYPE_REQUIRED;
    }
    return Optional.ofNullable(message);
  }

  private static String sorted() {
    return String.join(", ", TYPES.stream().sorted().toList());
  }
}
