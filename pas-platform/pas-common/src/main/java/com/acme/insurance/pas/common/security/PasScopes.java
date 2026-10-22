package com.acme.insurance.pas.common.security;

public final class PasScopes {
  public static final String POLICY_READ = "policy:read";
  public static final String POLICY_WRITE = "policy:write";
  public static final String UNDERWRITING_DECIDE = "underwriting:decide";
  public static final String BATCH_OPERATE = "batch:operate";

  private PasScopes() {}

  public static String authority(String scope) {
    return "SCOPE_" + scope;
  }
}
