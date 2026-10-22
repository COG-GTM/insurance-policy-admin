package com.acme.insurance.pas.common.security;

public final class PasScopes {
  public static final String POLICY_READ = "policy:read";
  public static final String POLICY_WRITE = "policy:write";
  public static final String PII_READ = "pii:read";
  public static final String UNDERWRITING_DECIDE = "underwriting:decide";
  public static final String BATCH_OPERATE = "batch:operate";
  public static final String POLICY_RENEW = "policy:renew";
  public static final String POLICY_ENDORSE = "policy:endorse";
  public static final String UNDERWRITING_OVERRIDE = "underwriting:override";

  private PasScopes() {}

  public static String authority(String scope) {
    return "SCOPE_" + scope;
  }
}
