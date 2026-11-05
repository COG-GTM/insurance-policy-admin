package com.acme.insurance.pas.policyservice.security;

/** OAuth 2.0 scopes the policy service checks (ADR 0001 D5). */
public final class PasScopes {

  /** Read policy headers and coverages within the caller's entitlement. */
  public static final String POLICY_READ = "pas.policy.read";

  /** Read the policyholder's contact details in clear; without it they are masked. */
  public static final String POLICYHOLDER_READ = "pas.policyholder.read";

  /** Renew a policy within the caller's entitlement (POLRNW, AB-526). */
  public static final String POLICY_RENEW = "pas.policy.renew";

  /** Endorse a policy within the caller's entitlement (POLEND, AB-526). */
  public static final String POLICY_ENDORSE = "pas.policy.endorse";

  /** Read the info and Prometheus management endpoints. */
  public static final String OPS_READ = "pas.ops.read";

  private PasScopes() {}

  public static String authority(String scope) {
    return "SCOPE_" + scope;
  }
}
