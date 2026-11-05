package com.acme.insurance.pas.policyservice.newbusiness;

import java.util.List;

/**
 * Error body of the write APIs. {@code message} is the single text the CICS transaction would show
 * (quirk Q-11: the last failed check wins); {@code violations} lists every problem.
 */
public record ApiError(String message, List<Violation> violations) {

  public ApiError {
    violations = List.copyOf(violations);
  }

  /** One problem with one request field. */
  public record Violation(String field, String message) {}
}
