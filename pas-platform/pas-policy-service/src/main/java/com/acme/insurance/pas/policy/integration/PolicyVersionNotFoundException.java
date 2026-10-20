package com.acme.insurance.pas.policy.integration;

/**
 * No {@code pas.policy_versions} row for the event's policy/version; retried, then dead-lettered.
 */
public class PolicyVersionNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PolicyVersionNotFoundException(String policyNumber, long version) {
    super("no snapshot of " + policyNumber + " at policyVersion " + version);
  }
}
