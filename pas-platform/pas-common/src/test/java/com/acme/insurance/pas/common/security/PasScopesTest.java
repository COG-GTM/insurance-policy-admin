package com.acme.insurance.pas.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PasScopesTest {
  @Test
  void exposesPolicyAndBatchScopes() {
    assertEquals("policy:read", PasScopes.POLICY_READ);
    assertEquals("policy:write", PasScopes.POLICY_WRITE);
    assertEquals("policy:renew", PasScopes.POLICY_RENEW);
    assertEquals("policy:endorse", PasScopes.POLICY_ENDORSE);
    assertEquals("pii:read", PasScopes.PII_READ);
    assertEquals("underwriting:decide", PasScopes.UNDERWRITING_DECIDE);
    assertEquals("underwriting:override", PasScopes.UNDERWRITING_OVERRIDE);
    assertEquals("batch:operate", PasScopes.BATCH_OPERATE);
    assertEquals("SCOPE_policy:read", PasScopes.authority(PasScopes.POLICY_READ));
  }
}
