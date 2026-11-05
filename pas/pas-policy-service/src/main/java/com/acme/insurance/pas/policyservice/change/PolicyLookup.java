package com.acme.insurance.pas.policyservice.change;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import java.util.Optional;

/** Entitlement check before the row lock, so a caller cannot lock policies it may not see. */
final class PolicyLookup {

  /** ACMEINS.POLICIES.POLICY_NUMBER is CHAR(12); longer keys cannot exist. */
  static final int POLICY_NUMBER_LENGTH = 12;

  private PolicyLookup() {}

  /** The locked policy, or not found / denied. Must run inside a transaction. */
  static ChangeResult<Policy> lockEntitled(
      PolicyJdbcRepository policies, String policyNumber, Entitlement entitlement) {
    if (policyNumber == null
        || policyNumber.isEmpty()
        || policyNumber.length() > POLICY_NUMBER_LENGTH) {
      return ChangeResult.notFound();
    }
    Optional<Policy> unlocked = policies.findPolicy(policyNumber);
    if (unlocked.isEmpty()) {
      return ChangeResult.notFound();
    }
    if (!permits(entitlement, unlocked.get())) {
      return ChangeResult.denied();
    }
    Optional<Policy> locked = policies.findPolicyForUpdate(policyNumber);
    if (locked.isEmpty()) {
      return ChangeResult.notFound();
    }
    return permits(entitlement, locked.get())
        ? ChangeResult.done(locked.get())
        : ChangeResult.denied();
  }

  private static boolean permits(Entitlement entitlement, Policy policy) {
    return entitlement.permits(policy.agentCode(), policy.branchCode());
  }

  /** Re-types a not-found or denied lookup for the caller's result. */
  static <T> ChangeResult<T> propagate(ChangeResult<Policy> lookup) {
    return new ChangeResult<>(lookup.outcome(), null, lookup.error());
  }
}
