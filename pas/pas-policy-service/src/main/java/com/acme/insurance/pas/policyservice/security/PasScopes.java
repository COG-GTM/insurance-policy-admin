package com.acme.insurance.pas.policyservice.security;

/** OAuth 2.0 scopes the policy service checks (ADR 0001 D5). */
public final class PasScopes {

  /** Read policy headers and coverages within the caller's entitlement. */
  public static final String POLICY_READ = "pas.policy.read";

  /** Read the policyholder's contact details in clear; without it they are masked. */
  public static final String POLICYHOLDER_READ = "pas.policyholder.read";

  /** Create new-business policies (replaces CICS PNEW) within the caller's entitlement. */
  public static final String POLICY_WRITE = "pas.policy.write";

  /** Run the underwriting decision (replaces CICS PUWR) on an entitled policy. */
  public static final String UNDERWRITING_DECIDE = "pas.underwriting.decide";

  /** Override the latest underwriting decision with a reason. */
  public static final String UNDERWRITING_OVERRIDE = "pas.underwriting.override";

  /** Read the info and Prometheus management endpoints. */
  public static final String OPS_READ = "pas.ops.read";

  private PasScopes() {}

  public static String authority(String scope) {
    return "SCOPE_" + scope;
  }
}
