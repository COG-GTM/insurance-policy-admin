package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.inquiry.CoverageView;
import com.acme.insurance.pas.policy.inquiry.PolicyView;
import java.util.List;

/** {@code POST /api/v1/policies} response: the stored policy and its default coverages. */
public record CreatedPolicyView(PolicyView policy, List<CoverageView> coverages) {

  public CreatedPolicyView {
    coverages = List.copyOf(coverages);
  }
}
