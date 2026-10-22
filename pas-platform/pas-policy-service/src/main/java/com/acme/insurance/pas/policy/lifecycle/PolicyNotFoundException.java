package com.acme.insurance.pas.policy.lifecycle;

/** Unknown or unentitled policy; both map to 404 so existence is not disclosed (Q-32). */
public final class PolicyNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PolicyNotFoundException() {
    super("Policy is unknown or not entitled");
  }
}
