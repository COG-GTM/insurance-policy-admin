package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.domain.claims.ClaimsSummary;
import com.acme.insurance.pas.policy.events.DocumentRequests;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.inquiry.PolicyInquiryRepository;
import com.acme.insurance.pas.policy.newbusiness.NewBusinessError;
import com.acme.insurance.pas.policy.platform.ApiRequestException;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import com.acme.insurance.pas.policy.repository.UnderwritingDecisionAudit;
import com.acme.insurance.pas.policy.repository.UnderwritingDecisionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PUWR on the new service. Decision and audit writes commit with their outbox records in the same
 * transaction. It publishes UnderwritingDecisionRecorded or UnderwritingReferralRequested and
 * requests the corresponding decision document through the outbox.
 */
@Service
public class UnderwritingService {
  private static final Pattern POLICY_NUMBER = Pattern.compile("[A-Za-z0-9-]{1,12}");

  private final PolicyRepository policies;
  private final PolicyInquiryRepository policyInquiry;
  private final PolicyHolderRepository holders;
  private final UnderwritingDecisionRepository decisions;
  private final ShadowUnderwritingRepository shadowResults;
  private final ClaimsHistoryPort claimsHistory;
  private final UnderwritingThresholds thresholds;
  private final OutboxWriter outbox;
  private final DocumentRequests documentRequests;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Dependencies are Spring-managed services, ports, and immutable configuration.")
  public UnderwritingService(
      PolicyRepository policies,
      PolicyInquiryRepository policyInquiry,
      PolicyHolderRepository holders,
      UnderwritingDecisionRepository decisions,
      ShadowUnderwritingRepository shadowResults,
      ClaimsHistoryPort claimsHistory,
      UnderwritingThresholds thresholds,
      OutboxWriter outbox,
      DocumentRequests documentRequests,
      Clock clock) {
    this.policies = policies;
    this.policyInquiry = policyInquiry;
    this.holders = holders;
    this.decisions = decisions;
    this.shadowResults = shadowResults;
    this.claimsHistory = claimsHistory;
    this.thresholds = thresholds;
    this.outbox = outbox;
    this.documentRequests = documentRequests;
    this.clock = clock;
  }

  public static boolean validPolicyNumber(String policyNumber) {
    return policyNumber != null && POLICY_NUMBER.matcher(policyNumber).matches();
  }

  public static String normalizePolicyNumber(String policyNumber) {
    return policyNumber == null ? null : policyNumber.toUpperCase(Locale.ROOT);
  }

  @Transactional
  public DecisionResponse decide(
      String policyNumber,
      CallerEntitlements entitlements,
      String subject,
      String correlationId,
      boolean shadow) {
    Policy policy =
        policies
            .findForUpdate(policyNumber, entitlements)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "Policy not found"));
    PolicyHolder holder =
        holders
            .findByCustId(policy.policyholderId())
            .orElseThrow(
                () ->
                    new ApiRequestException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "POLICY/CUSTOMER DATA NOT FOUND"));

    LocalDate today = LocalDate.now(clock);
    ClaimsSummary claims = claimsHistory.summarizeSince(policyNumber, today.minusYears(5));
    BigDecimal totalIncurred =
        claims.totalIncurred() == null ? BigDecimal.ZERO.setScale(2) : claims.totalIncurred();
    UnderwritingOutcome decision =
        UnderwritingRules.decide(
            new UnderwritingInput(
                holder.creditScore(),
                holder.riskTier() == null ? null : holder.riskTier().raw(),
                policy.totalPremium(),
                policy.coverageLimit(),
                totalIncurred,
                branchAccumulation(policy.branchCode()),
                claims.claimCount(),
                policy.branchCode()),
            thresholds);
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    if (shadow) {
      shadowResults.insert(policyNumber, decision);
      return response(
          null,
          policyNumber,
          today,
          decision.code().name(),
          decision.riskScore(),
          decision.reasons(),
          decision.lossRatio(),
          "SYSTEM",
          subject,
          now,
          null,
          null,
          true);
    }

    UnderwritingDecisionAudit draft =
        new UnderwritingDecisionAudit(
            null,
            policyNumber,
            today,
            decision.code().name(),
            decision.riskScore(),
            decision.reasons().getFirst(),
            decision.reasons(),
            decision.lossRatio(),
            "SYSTEM",
            subject,
            now,
            null,
            null,
            null);
    long decisionId = decisions.insert(draft);
    long policyVersion =
        policies.updateUnderwriting(
            policyNumber, decision.code().name(), decision.riskScore(), "UNDWRT");
    String eventType;
    UUID eventId;
    if (isReferral(decision.code().name())) {
      eventType = "UnderwritingReferralRequested";
      eventId =
          outbox.append(
              OutboxWriter.UNDERWRITING_TOPIC,
              eventType,
              policyNumber,
              policyVersion,
              correlationId,
              referralData(decisionId, decision, false, null));
    } else {
      eventType = "UnderwritingDecisionRecorded";
      eventId =
          outbox.append(
              OutboxWriter.UNDERWRITING_TOPIC,
              eventType,
              policyNumber,
              policyVersion,
              correlationId,
              decisionData(decisionId, decision, false, null));
    }
    documentRequests.append(
        policyNumber,
        policyVersion,
        correlationId,
        DocumentRequests.DocumentType.UNDERWRITING_DECISION,
        eventId,
        eventType);
    return response(
        decisionId,
        policyNumber,
        today,
        decision.code().name(),
        decision.riskScore(),
        decision.reasons(),
        decision.lossRatio(),
        "SYSTEM",
        subject,
        now,
        null,
        null,
        false);
  }

  @Transactional
  public DecisionResponse override(
      String policyNumber,
      long decisionId,
      CallerEntitlements entitlements,
      String subject,
      String correlationId,
      OverrideRequest request) {
    List<NewBusinessError> errors = validateOverride(request);
    if (!errors.isEmpty()) {
      throw new ApiRequestException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Override request validation failed", errors);
    }
    policies
        .findForUpdate(policyNumber, entitlements)
        .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "Policy not found"));
    UnderwritingDecisionAudit original =
        decisions
            .findById(policyNumber, decisionId)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "Decision not found"));
    UnderwritingDecisionAudit latest =
        decisions
            .findLatest(policyNumber)
            .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "Decision not found"));
    if (!latest.decisionId().equals(original.decisionId())) {
      throw new ApiRequestException(
          HttpStatus.CONFLICT, "Only the latest underwriting decision can be overridden");
    }
    String code = request.decisionCode().trim();
    String reason = request.reason().trim();
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    String shortSubject = subject.length() <= 8 ? subject : null;
    UnderwritingDecisionAudit draft =
        new UnderwritingDecisionAudit(
            null,
            policyNumber,
            LocalDate.now(clock),
            code,
            original.riskScore(),
            reason,
            List.of(reason),
            original.lossRatio(),
            shortSubject,
            subject,
            now,
            original.decisionId(),
            reason,
            shortSubject);
    long newDecisionId = decisions.insert(draft);
    long policyVersion =
        policies.updateUnderwriting(policyNumber, code, original.riskScore(), "UNDWRT");
    String eventType;
    UUID eventId;
    if (isReferral(code)) {
      eventType = "UnderwritingReferralRequested";
      eventId =
          outbox.append(
              OutboxWriter.UNDERWRITING_TOPIC,
              eventType,
              policyNumber,
              policyVersion,
              correlationId,
              referralData(
                  newDecisionId,
                  code,
                  original.riskScore(),
                  List.of(reason),
                  original.lossRatio(),
                  true,
                  original.decisionId()));
    } else {
      eventType = "UnderwritingDecisionRecorded";
      eventId =
          outbox.append(
              OutboxWriter.UNDERWRITING_TOPIC,
              eventType,
              policyNumber,
              policyVersion,
              correlationId,
              overrideData(newDecisionId, code, original, reason));
    }
    documentRequests.append(
        policyNumber,
        policyVersion,
        correlationId,
        DocumentRequests.DocumentType.UNDERWRITING_DECISION,
        eventId,
        eventType);
    return response(
        newDecisionId,
        policyNumber,
        LocalDate.now(clock),
        code,
        original.riskScore(),
        List.of(reason),
        original.lossRatio(),
        shortSubject,
        subject,
        now,
        original.decisionId(),
        reason,
        false);
  }

  public List<DecisionResponse> list(String policyNumber, CallerEntitlements entitlements) {
    if (policyInquiry.findPolicy(policyNumber, entitlements).isEmpty()) {
      throw new ApiRequestException(HttpStatus.NOT_FOUND, "Policy not found");
    }
    return decisions.findAuditRecordsByPolicyNumber(policyNumber).stream()
        .map(
            decision ->
                response(
                    decision.decisionId(),
                    decision.policyNumber(),
                    decision.decisionDate(),
                    decision.decisionCode(),
                    decision.riskScore(),
                    decision.reasons(),
                    decision.lossRatio(),
                    decision.underwriterId(),
                    decision.decidedBy(),
                    decision.createdTimestamp(),
                    decision.overridesDecisionId(),
                    decision.overrideReason(),
                    false))
        .toList();
  }

  private BigDecimal branchAccumulation(String branchCode) {
    if (branchCode == null || branchCode.isBlank()) {
      return BigDecimal.ZERO;
    }
    return policies.branchAccumulation(branchCode);
  }

  private static List<NewBusinessError> validateOverride(OverrideRequest request) {
    List<NewBusinessError> errors = new java.util.ArrayList<>();
    if (request == null
        || request.decisionCode() == null
        || !List.of("AP", "RS", "RM", "DC").contains(request.decisionCode().trim())) {
      errors.add(new NewBusinessError("decisionCode", "decisionCode must be AP, RS, RM, or DC"));
    }
    if (request == null || request.reason() == null || request.reason().isBlank()) {
      errors.add(new NewBusinessError("reason", "reason is required"));
    } else if (request.reason().length() > 200) {
      errors.add(new NewBusinessError("reason", "reason must not exceed 200 characters"));
    }
    return List.copyOf(errors);
  }

  private static boolean isReferral(String code) {
    return "RS".equals(code) || "RM".equals(code);
  }

  private static DecisionResponse response(
      Long decisionId,
      String policyNumber,
      LocalDate decisionDate,
      String decisionCode,
      int riskScore,
      List<String> reasons,
      BigDecimal lossRatio,
      String underwriterId,
      String decidedBy,
      OffsetDateTime createdTimestamp,
      Long overridesDecisionId,
      String overrideReason,
      boolean shadow) {
    return new DecisionResponse(
        decisionId,
        policyNumber,
        decisionDate,
        decisionCode,
        riskScore,
        reasons,
        lossRatio,
        underwriterId,
        decidedBy,
        createdTimestamp,
        overridesDecisionId,
        overrideReason,
        shadow);
  }

  private static Map<String, Object> decisionData(
      long decisionId, UnderwritingOutcome decision, boolean override, Long overridesDecisionId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("decisionId", decisionId);
    data.put("decisionCode", decision.code().name());
    data.put("riskScore", decision.riskScore());
    data.put("reasons", decision.reasons());
    data.put("lossRatio", decision.lossRatio());
    data.put("override", override);
    data.put("overridesDecisionId", overridesDecisionId);
    return data;
  }

  private static Map<String, Object> overrideData(
      long decisionId, String code, UnderwritingDecisionAudit original, String reason) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("decisionId", decisionId);
    data.put("decisionCode", code);
    data.put("riskScore", original.riskScore());
    data.put("reasons", List.of(reason));
    data.put("lossRatio", original.lossRatio());
    data.put("override", true);
    data.put("overridesDecisionId", original.decisionId());
    return data;
  }

  private static Map<String, Object> referralData(
      long decisionId, UnderwritingOutcome decision, boolean override, Long overridesDecisionId) {
    return referralData(
        decisionId,
        decision.code().name(),
        decision.riskScore(),
        decision.reasons(),
        decision.lossRatio(),
        override,
        overridesDecisionId);
  }

  private static Map<String, Object> referralData(
      long decisionId,
      String decisionCode,
      int riskScore,
      List<String> reasons,
      BigDecimal lossRatio,
      boolean override,
      Long overridesDecisionId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("trigger", "DECISION");
    data.put("decisionId", decisionId);
    data.put("decisionCode", decisionCode);
    data.put("riskScore", riskScore);
    data.put("reasons", reasons);
    data.put("lossRatio", lossRatio);
    data.put("override", override);
    data.put("overridesDecisionId", overridesDecisionId);
    return data;
  }
}
