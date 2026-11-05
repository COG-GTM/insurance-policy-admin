package com.acme.insurance.pas.policyservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtClaimsEntitlementResolverTest {

  private final JwtClaimsEntitlementResolver resolver = new JwtClaimsEntitlementResolver();

  @Test
  void readsWholeBookAgentAndBranchClaims() {
    Entitlement e =
        resolve(
            Map.of(
                "pas_book", "*",
                "pas_agents", List.of("AG1001", " AG2005 "),
                "pas_branches", "CHI1 DET1"));
    assertThat(e.wholeBook()).isTrue();
    assertThat(e.agentCodes()).containsExactlyInAnyOrder("AG1001", "AG2005");
    assertThat(e.branchCodes()).containsExactlyInAnyOrder("CHI1", "DET1");
  }

  @Test
  void wildcardsInsideListsAndMalformedClaimsGrantNothing() {
    Entitlement e =
        resolve(
            Map.of("pas_book", List.of("*"), "pas_agents", List.of("*", 42), "pas_branches", 7));
    assertThat(e).isEqualTo(new Entitlement(false, Set.of(), Set.of()));
  }

  @Test
  void nonJwtAuthenticationGetsNoEntitlement() {
    assertThat(resolver.resolve(new TestingAuthenticationToken("user", "pw")))
        .isEqualTo(Entitlement.NONE);
  }

  private Entitlement resolve(Map<String, Object> claims) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("s")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    claims.forEach(jwt::claim);
    return resolver.resolve(new JwtAuthenticationToken(jwt.build()));
  }
}
