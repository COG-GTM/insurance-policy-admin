package com.acme.insurance.pas.policy.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CallerEntitlementsTest {

  @Test
  void agentClaimMatchesPaddedCaseInsensitiveColumn() {
    CallerEntitlements e = new CallerEntitlements("ag1001", null, null);
    assertThat(e.canAccess("AG1001  ", "CHI1")).isTrue();
    assertThat(e.canAccess("AG2005", "CHI1")).isFalse();
  }

  @Test
  void branchClaimMatchesBranch() {
    CallerEntitlements e = new CallerEntitlements(null, "CHI1", Set.of());
    assertThat(e.canAccess("AG2005", "CHI1")).isTrue();
    assertThat(e.canAccess("AG3010", "DET1")).isFalse();
  }

  @Test
  void bookEntries() {
    assertThat(new CallerEntitlements(null, null, Set.of("*")).canAccess("X", "Y")).isTrue();
    assertThat(new CallerEntitlements(null, null, Set.of("book:*")).canAccess("X", "Y")).isTrue();
    CallerEntitlements e =
        new CallerEntitlements(null, null, Set.of("branch:det1", "agent:AG2005"));
    assertThat(e.canAccess("AG3010", "DET1")).isTrue();
    assertThat(e.canAccess("AG2005", "CHI1")).isTrue();
    assertThat(e.canAccess("AG1001", "CHI1")).isFalse();
  }

  @Test
  void malformedOrEmptyClaimsGrantNothing() {
    CallerEntitlements e =
        new CallerEntitlements(" ", "", Set.of("agent:", ":X", "region:MW", "AG1001"));
    assertThat(e.book()).isEmpty();
    assertThat(e.canAccess("AG1001", "CHI1")).isFalse();
    assertThat(e.canAccess(null, null)).isFalse();
  }

  @Test
  void readsClaimsFromJwtIncludingSpaceSeparatedBook() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("agent_code", "AG1001")
            .claim("book", "branch:DET1 agent:AG2005")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    CallerEntitlements e = CallerEntitlements.fromJwt(jwt);
    assertThat(e.agentCode()).isEqualTo("AG1001");
    assertThat(e.book()).containsExactlyInAnyOrder("branch:DET1", "agent:AG2005");
  }

  @Test
  void hasScopeReadsScopeAndScp() {
    Jwt scope =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("scope", "policy:read policy:pii:read")
            .build();
    Jwt scp =
        Jwt.withTokenValue("t").header("alg", "RS256").claim("scp", List.of("policy:read")).build();
    assertThat(PasScopes.hasScope(scope, PasScopes.POLICY_PII_READ)).isTrue();
    assertThat(PasScopes.hasScope(scp, PasScopes.POLICY_PII_READ)).isFalse();
    assertThat(PasScopes.hasScope(scp, PasScopes.POLICY_READ)).isTrue();
  }
}
