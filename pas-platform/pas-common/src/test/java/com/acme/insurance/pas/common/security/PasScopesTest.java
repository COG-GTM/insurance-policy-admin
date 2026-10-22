package com.acme.insurance.pas.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PasScopesTest {
  @Test
  void exposesPolicyAndBatchScopes() {
    assertEquals("policy:read", PasScopes.POLICY_READ);
    assertEquals("policy:write", PasScopes.POLICY_WRITE);
    assertEquals("underwriting:decide", PasScopes.UNDERWRITING_DECIDE);
    assertEquals("batch:operate", PasScopes.BATCH_OPERATE);
    assertEquals("SCOPE_policy:read", PasScopes.authority(PasScopes.POLICY_READ));
  }
}
