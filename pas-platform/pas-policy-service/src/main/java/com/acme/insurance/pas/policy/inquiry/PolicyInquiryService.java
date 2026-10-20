package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.policy.persistence.CoverageRepository;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import java.util.List;
import java.util.Optional;
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

  public PolicyInquiryService(
      PolicyRepository policies, CoverageRepository coverages, PolicyHolderRepository holders) {
    this.policies = policies;
    this.coverages = coverages;
    this.holders = holders;
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
