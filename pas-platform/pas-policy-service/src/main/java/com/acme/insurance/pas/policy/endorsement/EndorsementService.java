package com.acme.insurance.pas.policy.endorsement;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import com.acme.insurance.pas.domain.Endorsement;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.lifecycle.EndorsementRules;
import com.acme.insurance.pas.lifecycle.EndorsementType;
import com.acme.insurance.pas.lifecycle.LifecycleRejectedException;
import com.acme.insurance.pas.lifecycle.LifecycleRejectedException.Rejection;
import com.acme.insurance.pas.policy.events.DocumentRequests;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.inquiry.PolicyInquiryRepository;
import com.acme.insurance.pas.policy.lifecycle.PolicyAuditRepository;
import com.acme.insurance.pas.policy.lifecycle.PolicyNotFoundException;
import com.acme.insurance.pas.policy.lifecycle.ShadowResultRepository;
import com.acme.insurance.pas.policy.repository.CoverageRepository;
import com.acme.insurance.pas.policy.repository.EndorsementRepository;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POLEND on the new service. The policy row lock ({@code SELECT … FOR UPDATE}) replaces DB2's
 * update lock, so concurrent endorsements on one policy serialise and each sees the other's
 * sequence number and premium. The endorsement row, its effect (Q-13), the policy write and the
 * audit row (Q-15) and outbox records commit together or not at all (Q-14). It publishes
 * PolicyEndorsed and DocumentRequested through the outbox.
 */
@Service
public class EndorsementService {
  static final String PROGRAM = "POLEND";

  private final PolicyInquiryRepository inquiry;
  private final PolicyRepository policies;
  private final CoverageRepository coverages;
  private final EndorsementRepository endorsements;
  private final PolicyHolderRepository holders;
  private final PolicyAuditRepository audit;
  private final ShadowResultRepository shadow;
  private final Clock clock;
  private final OutboxWriter outbox;
  private final DocumentRequests documentRequests;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-managed collaborators are retained by design.")
  public EndorsementService(
      PolicyInquiryRepository inquiry,
      PolicyRepository policies,
      CoverageRepository coverages,
      EndorsementRepository endorsements,
      PolicyHolderRepository holders,
      PolicyAuditRepository audit,
      ShadowResultRepository shadow,
      Clock clock,
      OutboxWriter outbox,
      DocumentRequests documentRequests) {
    this.inquiry = inquiry;
    this.policies = policies;
    this.coverages = coverages;
    this.endorsements = endorsements;
    this.holders = holders;
    this.audit = audit;
    this.shadow = shadow;
    this.clock = clock;
    this.outbox = outbox;
    this.documentRequests = documentRequests;
  }

  @Transactional
  public EndorsementResponse endorse(
      String policyNumber,
      EndorsementRequest request,
      CallerEntitlements caller,
      String actor,
      String correlationId) {
    Policy policy =
        inquiry.lockPolicy(policyNumber, caller).orElseThrow(PolicyNotFoundException::new);
    Calculation calculation = calculate(policy, request, caller);
    endorsements.insert(
        new Endorsement(
            policy.policyNumber(),
            calculation.sequence(),
            calculation.type().code(),
            calculation.effectiveDate(),
            request.description(),
            calculation.adjustment(),
            OffsetDateTime.now(clock),
            PROGRAM));
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("type", calculation.type().code());
    details.put("effectiveDate", calculation.effectiveDate().toString());
    details.put("proRataFactor", calculation.factor());
    details.put("premiumAdjustment", calculation.adjustment());
    String status = applyEffect(policy, request, calculation, details);
    long policyVersion =
        policies.saveLifecycle(withPremiumAndStatus(policy, calculation.totalPremium(), status));
    audit.record(
        policy.policyNumber(),
        PolicyAuditRepository.ENDORSEMENT,
        String.valueOf(calculation.sequence()),
        actor,
        policy.totalPremium(),
        calculation.totalPremium(),
        details);
    Map<String, Object> eventData = new LinkedHashMap<>();
    eventData.put("endorsementSequence", calculation.sequence());
    eventData.put("endorsementType", calculation.type().code());
    eventData.put("effectiveDate", calculation.effectiveDate());
    eventData.put("premiumAdjustment", calculation.adjustment());
    eventData.put("totalPremium", calculation.totalPremium());
    eventData.put("policyStatus", status);
    UUID endorsedEventId =
        outbox.append(
            OutboxWriter.POLICY_TOPIC,
            "PolicyEndorsed",
            policy.policyNumber(),
            policyVersion,
            correlationId,
            eventData);
    documentRequests.append(
        policy.policyNumber(),
        policyVersion,
        correlationId,
        DocumentRequests.DocumentType.ENDORSEMENT,
        endorsedEventId);
    return response(policy, request, calculation, status, "APPLIED");
  }

  /** SHADOW route: compute the endorsement and record the outcome in pas_shadow only. */
  @Transactional(noRollbackFor = LifecycleRejectedException.class)
  public EndorsementResponse shadow(
      String policyNumber, EndorsementRequest request, CallerEntitlements caller) {
    Policy policy =
        inquiry.findPolicy(policyNumber, caller).orElseThrow(PolicyNotFoundException::new);
    try {
      Calculation calculation = calculate(policy, request, caller);
      String status =
          calculation.type() == EndorsementType.CANCEL
              ? PolicyStatus.CANCELLED.code()
              : raw(policy.policyStatus());
      shadow.recordEndorsement(
          new ShadowResultRepository.EndorsementResult(
              policy.policyNumber(),
              calculation.type().code(),
              calculation.effectiveDate(),
              "ENDORSED",
              calculation.sequence(),
              calculation.adjustment(),
              calculation.factor(),
              calculation.totalPremium()));
      return response(policy, request, calculation, status, "SHADOW");
    } catch (LifecycleRejectedException exception) {
      shadow.recordEndorsement(
          new ShadowResultRepository.EndorsementResult(
              policy.policyNumber(),
              shadowType(request.type()),
              request.effectiveDate(),
              exception.rejection().name(),
              null,
              null,
              null,
              null));
      throw exception;
    }
  }

  private Calculation calculate(
      Policy policy, EndorsementRequest request, CallerEntitlements caller) {
    EndorsementRules.checkActive(raw(policy.policyStatus()));
    EndorsementType type = EndorsementType.fromCode(request.type());
    checkDetails(type, request);
    LocalDate date =
        request.effectiveDate() == null ? LocalDate.now(clock) : request.effectiveDate();
    BigDecimal factor =
        EndorsementRules.proRataFactor(policy.effectiveDate(), policy.expiryDate(), date);
    validateEffect(policy, request, type, date, caller);
    int sequence = EndorsementRules.nextSequence(endorsements.maxSequence(policy.policyNumber()));
    BigDecimal adjustment = EndorsementRules.PREMIUM_ADJUSTMENT;
    BigDecimal total = EndorsementRules.adjustedPremium(policy.totalPremium(), adjustment);
    return new Calculation(type, date, factor, sequence, adjustment, total);
  }

  private static void checkDetails(EndorsementType type, EndorsementRequest request) {
    boolean valid =
        switch (type) {
          case COVERAGE_ADD -> request.coverage() != null;
          case COVERAGE_REMOVE -> request.coverageSeq() != null;
          case LIMIT_CHANGE -> request.coverageSeq() != null && request.newLimit() != null;
          case ADDRESS_CHANGE -> request.address() != null;
          case CANCEL -> true;
        };
    if (!valid) {
      throw new LifecycleRejectedException(Rejection.ENDORSEMENT_DETAILS_INVALID);
    }
  }

  /** Read-only checks shared by NEW and SHADOW so both reject the same requests. */
  private void validateEffect(
      Policy policy,
      EndorsementRequest request,
      EndorsementType type,
      LocalDate date,
      CallerEntitlements caller) {
    switch (type) {
      case COVERAGE_ADD -> coverageType(policy, request.coverage());
      case COVERAGE_REMOVE -> {
        LocalDate start =
            coverages
                .activeCoverageStart(policy.policyNumber(), request.coverageSeq())
                .orElseThrow(() -> new LifecycleRejectedException(Rejection.COVERAGE_NOT_FOUND));
        if (date.isBefore(start)) {
          throw new LifecycleRejectedException(Rejection.COVERAGE_NOT_FOUND);
        }
      }
      case LIMIT_CHANGE ->
          coverages
              .activeCoverageStart(policy.policyNumber(), request.coverageSeq())
              .orElseThrow(() -> new LifecycleRejectedException(Rejection.COVERAGE_NOT_FOUND));
      case ADDRESS_CHANGE -> {
        if (inquiry.holderHasPoliciesOutside(policy.policyholderId(), caller)) {
          throw new LifecycleRejectedException(Rejection.ADDRESS_CHANGE_NOT_PERMITTED);
        }
      }
      case CANCEL -> {}
    }
  }

  /** Known codes plus legacy codes already carried by this policy type (Q-43). */
  private Coded<CoverageType> coverageType(
      Policy policy, EndorsementRequest.CoverageAddition addition) {
    String code = addition.coverageType().trim().toUpperCase(Locale.ROOT);
    Coded<CoverageType> coverageType = Coded.of(CoverageType.class, code);
    if (!coverageType.isKnown() && !coverages.coverageTypeInUse(raw(policy.policyType()), code)) {
      throw new LifecycleRejectedException(Rejection.ENDORSEMENT_DETAILS_INVALID);
    }
    return coverageType;
  }

  /** Q-13: apply the change in the same transaction; returns the resulting policy status. */
  private String applyEffect(
      Policy policy,
      EndorsementRequest request,
      Calculation calculation,
      Map<String, Object> details) {
    String policyNumber = policy.policyNumber();
    switch (calculation.type()) {
      case COVERAGE_ADD -> {
        EndorsementRequest.CoverageAddition addition = request.coverage();
        Coded<CoverageType> coverageType = coverageType(policy, addition);
        int sequence = coverages.nextSequence(policyNumber);
        coverages.insert(
            new Coverage(
                policyNumber,
                sequence,
                coverageType,
                addition.description(),
                zeroIfNull(addition.coverageLimit()),
                zeroIfNull(addition.deductible()),
                zeroIfNull(addition.premium()),
                calculation.effectiveDate(),
                policy.expiryDate(),
                Coded.of(CoverageStatus.ACTIVE),
                100,
                null,
                null));
        details.put("coverageSeq", sequence);
        details.put("coverageType", coverageType.raw());
      }
      case COVERAGE_REMOVE -> {
        requireCoverage(
            coverages.cancel(policyNumber, request.coverageSeq(), calculation.effectiveDate()));
        details.put("coverageSeq", request.coverageSeq());
      }
      case LIMIT_CHANGE -> {
        requireCoverage(
            coverages.changeLimit(policyNumber, request.coverageSeq(), request.newLimit()));
        details.put("coverageSeq", request.coverageSeq());
        details.put("newLimit", request.newLimit());
      }
      case ADDRESS_CHANGE -> {
        EndorsementRequest.AddressChange address = request.address();
        requireOne(
            holders.changeAddress(
                policy.policyholderId(),
                address.addrLine1(),
                address.addrLine2(),
                address.city(),
                address.stateCode().toUpperCase(Locale.ROOT),
                address.zipCode()));
        details.put("changedFields", java.util.List.of("address"));
      }
      case CANCEL -> {
        details.put(
            "coveragesCancelled",
            coverages.cancelActive(policyNumber, calculation.effectiveDate()));
        return PolicyStatus.CANCELLED.code();
      }
    }
    return raw(policy.policyStatus());
  }

  private static void requireCoverage(int rows) {
    if (rows != 1) {
      throw new LifecycleRejectedException(Rejection.COVERAGE_NOT_FOUND);
    }
  }

  private static void requireOne(int rows) {
    if (rows != 1) {
      throw new LifecycleRejectedException(Rejection.ENDORSEMENT_DETAILS_INVALID);
    }
  }

  private static Policy withPremiumAndStatus(Policy policy, BigDecimal total, String status) {
    return new Policy(
        policy.policyNumber(),
        policy.policyType(),
        Coded.of(PolicyStatus.class, status),
        policy.effectiveDate(),
        policy.expiryDate(),
        policy.policyholderId(),
        policy.agentCode(),
        policy.branchCode(),
        total,
        policy.deductible(),
        policy.coverageLimit(),
        policy.inceptionDate(),
        policy.renewalCount(),
        policy.uwStatus(),
        policy.riskScore(),
        policy.webIndicator(),
        policy.apiFlag(),
        policy.lastUpdated(),
        PROGRAM);
  }

  private static EndorsementResponse response(
      Policy policy,
      EndorsementRequest request,
      Calculation calculation,
      String status,
      String mode) {
    return new EndorsementResponse(
        policy.policyNumber(),
        mode,
        calculation.sequence(),
        calculation.type().code(),
        calculation.effectiveDate(),
        request.description(),
        calculation.factor(),
        calculation.adjustment(),
        calculation.totalPremium(),
        status);
  }

  /** Only validated codes reach pas_shadow; anything else is recorded as null. */
  private static String shadowType(String type) {
    try {
      return EndorsementType.fromCode(type).code();
    } catch (LifecycleRejectedException exception) {
      return null;
    }
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String raw(Coded<?> value) {
    return value == null ? null : value.raw();
  }

  private record Calculation(
      EndorsementType type,
      LocalDate effectiveDate,
      BigDecimal factor,
      int sequence,
      BigDecimal adjustment,
      BigDecimal totalPremium) {}
}
