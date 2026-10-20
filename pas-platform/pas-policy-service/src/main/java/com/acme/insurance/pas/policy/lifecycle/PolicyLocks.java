package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import java.util.Optional;

/** Object-level authorisation plus the row lock taken before a policy change. */
final class PolicyLocks {

  private PolicyLocks() {}

  /**
   * Locks the policy row and returns it only if the caller is entitled to it; a policy the caller
   * may not see is reported as absent (404, ADR 0001). The entitlement check runs on the row read
   * under the lock, so it cannot race a concurrent agent/branch change.
   */
  static Optional<PolicyEntity> lockEntitled(
      PolicyRepository policies, String policyNumber, CallerEntitlements caller) {
    if (policyNumber == null || policyNumber.isBlank()) {
      return Optional.empty();
    }
    return policies
        .findForUpdate(policyNumber)
        .filter(p -> caller.canAccess(p.getAgentCode(), p.getBranchCode()));
  }
}
