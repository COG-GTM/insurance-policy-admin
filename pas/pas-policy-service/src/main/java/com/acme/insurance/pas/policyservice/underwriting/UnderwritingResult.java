package com.acme.insurance.pas.policyservice.underwriting;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import java.util.List;

/** Outcome of an underwriting or override request. */
public sealed interface UnderwritingResult {

  /** 201/200: the decision row as stored. */
  record Done(UnderwritingDecision decision, List<String> reasons) implements UnderwritingResult {
    public Done {
      reasons = List.copyOf(reasons);
    }
  }

  /** 400: the request cannot be processed. */
  record Invalid(String message, List<String> violations) implements UnderwritingResult {
    public Invalid {
      violations = List.copyOf(violations);
    }
  }

  /** 404: no such policy, or not visible to the caller. */
  record NotFound() implements UnderwritingResult {}

  /** 409: nothing to override, or the decision changed meanwhile. */
  record Conflict(String message) implements UnderwritingResult {}
}
