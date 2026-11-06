package com.acme.insurance.pas.policyservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerIdentityTest {

  static JwtAuthenticationToken token(Object legacyUser) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", "user-1");
    claims.put("azp", "broker-portal");
    if (legacyUser != null) {
      claims.put(CallerIdentity.LEGACY_USER_CLAIM, legacyUser);
    }
    Jwt jwt =
        new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
    return new JwtAuthenticationToken(jwt, List.of(), "user-1");
  }

  @Test
  void validClaimIsThePersonalUserId() {
    CallerIdentity c = CallerIdentity.of(token("UW00042"));
    assertThat(c.legacyUser()).isEqualTo("UW00042");
    assertThat(c.legacyUserFromToken()).isTrue();
    assertThat(c.clientId()).isEqualTo("broker-portal");
  }

  @Test
  void missingOrInvalidClaimFallsBackToTheServiceIdAndIsMarked() {
    for (Object claim : new Object[] {null, "uw00042", "UW0004200", "", 42}) {
      CallerIdentity c = CallerIdentity.of(token(claim));
      assertThat(c.legacyUser()).as("%s", claim).isEqualTo(CallerIdentity.SERVICE_USER);
      assertThat(c.legacyUserFromToken()).as("%s", claim).isFalse();
    }
  }
}
