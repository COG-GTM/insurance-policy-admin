package com.acme.insurance.pas.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PasClaimsTest {
  @Test
  void exposesStandardClaimNames() {
    assertEquals("agent_id", PasClaims.AGENT_ID);
    assertEquals("branch_codes", PasClaims.BRANCH_CODES);
    assertEquals("book", PasClaims.BOOK);
    assertEquals("ALL", PasClaims.BOOK_ALL);
  }
}
