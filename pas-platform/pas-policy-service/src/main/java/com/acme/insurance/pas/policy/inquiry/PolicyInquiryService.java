package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.policy.persistence.CoverageRepository;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.persistence.PremiumRepository;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read path that replaces POLQRY / the facade. A policy the caller is not entitled to is reported
 * exactly like a missing one ({@link Optional#empty()} → 404), per ADR 0001 D5.
 */
@Service
@Transactional(readOnly = true)
public class PolicyInquiryService {

  private final PolicyRepository policies;
  private final CoverageRepository coverages;
  private final PolicyHolderRepository holders;
  private static final String NO_CODE = "\u0001";

  private final PremiumRepository premiums;

  public PolicyInquiryService(
      PolicyRepository policies,
      CoverageRepository coverages,
      PolicyHolderRepository holders,
      PremiumRepository premiums) {
    this.policies = policies;
    this.coverages = coverages;
    this.holders = holders;
    this.premiums = premiums;
  }

  /**
   * Broker Portal book listing (AB-528): policies of {@code agentCode} and/or {@code branchCode},
   * keeping only those the caller may see (others are silently omitted, never 403).
   */
  public List<PolicyView> book(
      String agentCode,
      String branchCode,
      String status,
      int page,
      int size,
      CallerEntitlements caller) {
    return policies
        .findBook(
            normaliseCode(agentCode),
            normaliseCode(branchCode),
            blankToNull(status),
            caller.wholeBook(),
            nonEmpty(caller.agentScope()),
            nonEmpty(caller.branchScope()),
            size,
            (long) page * size)
        .stream()
        .filter(p -> caller.canAccess(p.getAgentCode(), p.getBranchCode()))
        .map(PolicyView::from)
        .toList();
  }

  /** {@code IN ()} is invalid SQL; an empty grant set becomes one value that matches no code. */
  private static Collection<String> nonEmpty(Set<String> codes) {
    return codes.isEmpty() ? List.of(NO_CODE) : codes;
  }

  private static String normaliseCode(String s) {
    String v = blankToNull(s);
    return v == null ? null : v.toUpperCase(Locale.ROOT);
  }

  /** Premium history, newest term first (Broker Portal, AB-528). */
  public Optional<List<PremiumView>> premiums(String policyNumber, CallerEntitlements caller) {
    return entitledPolicy(policyNumber, caller)
        .map(
            p ->
                premiums
                    .findByIdPolicyNumberOrderByIdTermEffectiveDateDescIdCoverageSeqAsc(
                        p.getPolicyNumber())
                    .stream()
                    .map(PremiumView::from)
                    .toList());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  public Optional<PolicyView> policy(String policyNumber, CallerEntitlements caller) {
    return entitledPolicy(policyNumber, caller).map(PolicyView::from);
  }

  /** All coverages in {@code SEQUENCE_NUM} order; POLQRY's 20-row screen limit is not carried. */
  public Optional<List<CoverageView>> coverages(String policyNumber, CallerEntitlements caller) {
    return entitledPolicy(policyNumber, caller)
        .map(
            p ->
                coverages.findByIdPolicyNumberOrderByIdSequenceNumAsc(p.getPolicyNumber()).stream()
                    .map(CoverageView::from)
                    .toList());
  }

  public Optional<PolicyHolderView> holder(
      String policyNumber, CallerEntitlements caller, boolean piiAllowed) {
    return entitledPolicy(policyNumber, caller)
        .map(PolicyEntity::getPolicyholderId)
        .flatMap(holders::findById)
        .map(h -> piiAllowed ? PolicyHolderView.unmasked(h) : PolicyHolderView.masked(h));
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
