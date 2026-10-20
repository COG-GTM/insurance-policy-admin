package com.acme.insurance.pas.policy.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/** OAuth2 scopes from ADR 0001 D5 and the matching Spring Security authorities. */
public final class PasScopes {

  public static final String POLICY_READ = "policy:read";
  public static final String POLICY_PII_READ = "policy:pii:read";
  public static final String POLICY_WRITE = "policy:write";
  public static final String UNDERWRITING_DECIDE = "underwriting:decide";
  public static final String RATING_QUOTE = "rating:quote";
  public static final String BATCH_OPERATE = "batch:operate";

  private PasScopes() {}

  /** Authority name Spring Security derives from a JWT {@code scope}/{@code scp} entry. */
  public static String authority(String scope) {
    return "SCOPE_" + scope;
  }

  /**
   * True when the token's {@code scope} (space separated) or {@code scp} claim has {@code scope}.
   */
  public static boolean hasScope(Jwt jwt, String scope) {
    if (jwt == null) {
      return false;
    }
    Object claim = jwt.hasClaim("scope") ? jwt.getClaim("scope") : jwt.getClaim("scp");
    Collection<?> scopes =
        claim instanceof String s
            ? List.of(s.trim().split("\\s+"))
            : claim instanceof Collection<?> c ? c : List.of();
    return scopes.stream().anyMatch(v -> scope.equals(String.valueOf(v)));
  }
}
