package com.acme.insurance.pas.policyservice.change;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.persistence.PolicyChangeAudit;
import com.acme.insurance.pas.persistence.PolicyChangeAuditJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.change.PolicyChangeConfiguration.OpenClaimsRule;
import com.acme.insurance.pas.policyservice.change.PolicyChangeConfiguration.PolicyChangeClock;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import com.acme.insurance.pas.rating.PolicyChangeError;
import com.acme.insurance.pas.rating.RatingOverflowException;
import com.acme.insurance.pas.rating.RenewalCalculator;
import com.acme.insurance.pas.rating.RenewalQuote;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy renewal (replaces CICS PRWL / POLRNW). One transaction: the policy row is locked, the new
 * term, premium and renewal count are written, the active coverages re-dated and the audit row
 * inserted, or nothing is (fixes Q-07). Underwriting status is reset to pending without an
 * underwriting request, as POLRNW does (Q-39).
 */
@Service
public class RenewalService {

  /**
   * UPDATED_BY keeps the legacy program id until the 8-character mapping is signed off (Q-518-1).
   */
  static final String UPDATED_BY = "POLRNW";

  private final PolicyJdbcRepository policies;
  private final PolicyChangeAuditJdbcRepository audit;
  private final RenewalCalculator calculator;
  private final OpenClaimsRule openClaims;
  private final PolicyChangeClock clock;

  public RenewalService(
      PolicyJdbcRepository policies,
      PolicyChangeAuditJdbcRepository audit,
      RenewalCalculator calculator,
      OpenClaimsRule openClaims,
      PolicyChangeClock clock) {
    this.policies = policies;
    this.audit = audit;
    this.calculator = calculator;
    this.openClaims = openClaims;
    this.clock = clock;
  }

  /**
   * @param expectedExpiryDate when present, the renewal only happens while the policy still expires
   *     on that date, so a retried request cannot renew the policy twice
   */
  @Transactional
  public ChangeResult<Renewal> renew(
      String policyNumber, LocalDate expectedExpiryDate, Entitlement entitlement, Actor actor) {
    ChangeResult<Policy> lookup = PolicyLookup.lockEntitled(policies, policyNumber, entitlement);
    if (lookup.outcome() != ChangeResult.Outcome.DONE) {
      return PolicyLookup.propagate(lookup);
    }
    Policy current = lookup.value();
    if (!current.policyStatus().map(PolicyStatus::renewable).orElse(false)) {
      return ChangeResult.rejected(PolicyChangeError.NOT_ELIGIBLE_FOR_RENEWAL);
    }
    if (expectedExpiryDate != null && !expectedExpiryDate.equals(current.expiryDate())) {
      return ChangeResult.conflict(PolicyChangeError.EXPIRY_DATE_CHANGED);
    }
    if (openClaims.blocks(current.policyNumber())) {
      return ChangeResult.rejected(PolicyChangeError.TOO_MANY_OPEN_CLAIMS);
    }
    RenewalQuote quote;
    try {
      quote =
          calculator.quote(current.totalPremium(), current.expiryDate(), current.renewalCount());
    } catch (RatingOverflowException e) {
      return ChangeResult.rejected(PolicyChangeError.PREMIUM_OUT_OF_RANGE);
    }
    LocalDateTime now = LocalDateTime.now(clock.clock());
    Policy renewed =
        new Policy(
            current.policyNumber(),
            current.policyTypeCode(),
            PolicyStatus.ACTIVE.code(),
            quote.effectiveDate(),
            quote.expiryDate(),
            current.policyholderId(),
            current.agentCode(),
            current.branchCode(),
            quote.newPremium(),
            current.deductible(),
            current.coverageLimit(),
            current.inceptionDate(),
            quote.renewalCount(),
            UnderwritingStatus.PENDING.code(),
            current.riskScore(),
            current.webIndicator(),
            current.apiFlag(),
            now,
            UPDATED_BY);
    policies.updateRenewal(renewed);
    policies.redateActiveCoverages(
        renewed.policyNumber(), renewed.effectiveDate(), renewed.expiryDate());
    audit.insert(
        new PolicyChangeAudit(
            renewed.policyNumber(),
            PolicyChangeAudit.Operation.RENEWAL,
            UPDATED_BY,
            null,
            null,
            actor.principal(),
            actor.clientId(),
            actor.correlationId(),
            current.policyStatusCode(),
            renewed.policyStatusCode(),
            current.effectiveDate(),
            current.expiryDate(),
            renewed.effectiveDate(),
            renewed.expiryDate(),
            current.totalPremium(),
            quote.newPremium().subtract(quote.previousPremium()),
            quote.newPremium(),
            quote.renewalCount(),
            now));
    return ChangeResult.done(new Renewal(renewed, quote));
  }

  /** The renewed policy and how its premium was priced. */
  public record Renewal(Policy policy, RenewalQuote quote) {}
}
