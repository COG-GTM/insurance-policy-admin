package com.acme.insurance.pas.policyservice.newbusiness;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import java.util.List;

/** Outcome of a create-policy request. */
public sealed interface NewPolicyResult {

  record Created(Policy policy, List<Coverage> coverages) implements NewPolicyResult {
    public Created {
      coverages = List.copyOf(coverages);
    }
  }

  /** 400: a field does not fit its column. */
  record Malformed(ApiError error) implements NewPolicyResult {}

  /** 422: POLNEW validation failed. */
  record Invalid(ApiError error) implements NewPolicyResult {}

  /** 403: the agent and branch are outside the caller's entitlement. */
  record Forbidden() implements NewPolicyResult {}
}
