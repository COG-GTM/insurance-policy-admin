package com.acme.insurance.pas.policy.underwriting;

/** A manual override needs an earlier underwriting decision on the policy (409). */
public class NoDecisionToOverrideException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NoDecisionToOverrideException() {
    super("policy has no underwriting decision to override");
  }
}
