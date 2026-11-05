package com.acme.insurance.pas.policyservice.newbusiness;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.events.PasEvents;
import com.acme.insurance.pas.persistence.DefaultCoverage;
import com.acme.insurance.pas.persistence.DefaultCoverageJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyHolderJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.persistence.PolicySequence;
import com.acme.insurance.pas.policyservice.audit.AuditWriter;
import com.acme.insurance.pas.policyservice.config.BusinessClock;
import com.acme.insurance.pas.policyservice.events.OutboxWriter;
import com.acme.insurance.pas.policyservice.security.CallerIdentity;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * New business (replaces CICS PNEW / POLNEW). One transaction: policy number from POLICY_SEQ, the
 * POLICIES row (status PN, UW status PN), the default coverages of the type, the audit row and the
 * policy-created and underwriting-requested events in the outbox. Either everything commits or
 * nothing does; POLNEW's ignored TD queue failure cannot happen (quirk Q-13).
 */
@Service
@EnableConfigurationProperties(NewBusinessProperties.class)
public class NewBusinessService {

  /** POLICIES.UPDATED_BY of a new policy (8-character service id, Q-518-1). */
  static final String UPDATED_BY = "POLNEW";

  private final PolicyJdbcRepository policies;
  private final PolicyHolderJdbcRepository holders;
  private final DefaultCoverageJdbcRepository defaults;
  private final PolicySequence sequence;
  private final OutboxWriter outbox;
  private final AuditWriter audit;
  private final BusinessClock clock;
  private final NewBusinessProperties properties;
  private final PasEvents events;

  public NewBusinessService(
      PolicyJdbcRepository policies,
      PolicyHolderJdbcRepository holders,
      DefaultCoverageJdbcRepository defaults,
      PolicySequence sequence,
      OutboxWriter outbox,
      AuditWriter audit,
      BusinessClock clock,
      NewBusinessProperties properties) {
    this.policies = policies;
    this.holders = holders;
    this.defaults = defaults;
    this.sequence = sequence;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
    this.properties = properties;
    this.events = new PasEvents(clock.clock());
  }

  @Transactional
  public NewPolicyResult create(
      NewPolicyRequest request,
      Entitlement entitlement,
      CallerIdentity caller,
      String correlationId) {
    List<ApiError.Violation> format = NewPolicyValidator.format(request);
    if (!format.isEmpty()) {
      return new NewPolicyResult.Malformed(new ApiError(NewPolicyValidator.INVALID_INPUT, format));
    }
    // Entitlement before the holder lookup, so a narrow book cannot probe which holder ids exist.
    if (!entitlement.permits(request.agentCode(), request.branchCode())) {
      return new NewPolicyResult.Forbidden();
    }
    LocalDate today = clock.today();
    List<ApiError.Violation> violations =
        NewPolicyValidator.business(request, today, id -> holders.find(id).isPresent());
    if (!violations.isEmpty()) {
      return new NewPolicyResult.Invalid(
          new ApiError(violations.get(violations.size() - 1).message(), violations));
    }
    String policyNumber = properties.policyNumber(sequence.next());
    LocalDate effective = request.effectiveDate();
    LocalDate expiry = effective.plusMonths(properties.termMonths());
    Policy policy =
        new Policy(
            policyNumber,
            request.policyType(),
            "PN",
            effective,
            expiry,
            request.policyholderId(),
            request.agentCode(),
            request.branchCode(),
            orZero(request.totalPremium()),
            orZero(request.deductible()),
            request.coverageLimit(),
            effective,
            0,
            "PN",
            0,
            "N",
            "N",
            null,
            UPDATED_BY);
    policies.insert(policy);
    List<Coverage> coverages = new ArrayList<>();
    for (DefaultCoverage d : defaults.forPolicyType(request.policyType())) {
      Coverage c =
          new Coverage(
              policyNumber,
              d.sequenceNum(),
              d.coverageType(),
              d.description(),
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              effective,
              expiry,
              "AC",
              100,
              null,
              null);
      policies.insert(c);
      coverages.add(c);
    }
    audit.record(
        AuditWriter.POLICY_CREATED,
        policyNumber,
        caller,
        correlationId,
        Map.of(),
        after(policy, coverages));
    outbox.append(events.policyCreated(policy, coverages, correlationId));
    outbox.append(events.underwritingRequested(policy, correlationId));
    return new NewPolicyResult.Created(policy, coverages);
  }

  private static Map<String, Object> after(Policy p, List<Coverage> coverages) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("policyType", p.policyTypeCode());
    m.put("policyStatus", p.policyStatusCode());
    m.put("uwStatus", p.uwStatusCode());
    m.put("effectiveDate", p.effectiveDate().toString());
    m.put("expiryDate", p.expiryDate().toString());
    m.put("policyholderId", p.policyholderId());
    m.put("agentCode", p.agentCode());
    m.put("branchCode", p.branchCode());
    m.put("totalPremium", p.totalPremium());
    m.put("deductible", p.deductible());
    m.put("coverageLimit", p.coverageLimit());
    m.put("coverages", coverages.stream().map(Coverage::coverageTypeCode).toList());
    return m;
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
