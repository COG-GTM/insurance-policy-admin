package com.acme.insurance.pas.policyservice.inquiry;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolderContact;
import com.acme.insurance.pas.persistence.PolicyHolderJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Policy inquiry (replaces CICS PQRY / POLQRY and the facade's PolicyRepository). Every read is
 * checked against the caller's book-of-business entitlement before anything is returned. API_FLAG
 * stays informational, as in the facade (Q-24, FU-10).
 */
@Service
public class PolicyInquiryService {

  /** ACMEINS.POLICIES.POLICY_NUMBER is CHAR(12); longer keys cannot exist. */
  static final int POLICY_NUMBER_LENGTH = 12;

  private final PolicyJdbcRepository policies;
  private final PolicyHolderJdbcRepository holders;

  public PolicyInquiryService(PolicyJdbcRepository policies, PolicyHolderJdbcRepository holders) {
    this.policies = policies;
    this.holders = holders;
  }

  public InquiryResult<Policy> policy(String policyNumber, Entitlement entitlement) {
    if (policyNumber == null || policyNumber.length() > POLICY_NUMBER_LENGTH) {
      return InquiryResult.notFound();
    }
    Optional<Policy> policy = policies.findPolicy(policyNumber);
    if (policy.isEmpty()) {
      return InquiryResult.notFound();
    }
    Policy p = policy.get();
    return entitlement.permits(p.agentCode(), p.branchCode())
        ? InquiryResult.found(p)
        : InquiryResult.denied();
  }

  public InquiryResult<List<Coverage>> coverages(String policyNumber, Entitlement entitlement) {
    InquiryResult<Policy> policy = policy(policyNumber, entitlement);
    if (policy.outcome() != InquiryResult.Outcome.FOUND) {
      return new InquiryResult<>(policy.outcome(), null);
    }
    return InquiryResult.found(policies.findCoverages(policy.value().policyNumber()));
  }

  public InquiryResult<PolicyHolderContact> policyHolder(
      String policyNumber, Entitlement entitlement, boolean clearPersonalData) {
    InquiryResult<Policy> policy = policy(policyNumber, entitlement);
    if (policy.outcome() != InquiryResult.Outcome.FOUND) {
      return new InquiryResult<>(policy.outcome(), null);
    }
    String custId = policy.value().policyholderId();
    if (custId == null) {
      return InquiryResult.notFound();
    }
    Optional<PolicyHolderContact> contact =
        clearPersonalData ? holders.findContact(custId) : holders.findMaskedContact(custId);
    return contact.map(InquiryResult::found).orElseGet(InquiryResult::notFound);
  }
}
