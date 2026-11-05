package com.acme.insurance.pas.policyservice.security;

import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who makes a write: the token subject for the audit table, the OAuth client, and the 8-character
 * id for the ACMEINS audit columns (quirk Q-518-1). The 8-character id comes from the token claim
 * {@value #LEGACY_USER_CLAIM} when the IdP issues one; otherwise {@value #SERVICE_USER} (the user
 * id mapping is FU-525-3).
 */
public record CallerIdentity(String principal, String clientId, String legacyUser) {

  public static final String LEGACY_USER_CLAIM = "pas_legacy_user";
  public static final String SERVICE_USER = "PASAPI";
  private static final Pattern LEGACY_USER = Pattern.compile("[A-Z0-9#@$]{1,8}");
  private static final Pattern SAFE_CLIENT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  public static CallerIdentity of(Authentication authentication) {
    String principal = authentication.getName();
    String client = null;
    String legacy = null;
    if (authentication instanceof JwtAuthenticationToken jwt) {
      for (String claim : new String[] {"azp", "client_id"}) {
        if (client == null && jwt.getToken().getClaims().get(claim) instanceof String s) {
          client = SAFE_CLIENT.matcher(s).matches() ? s : "invalid";
        }
      }
      if (jwt.getToken().getClaims().get(LEGACY_USER_CLAIM) instanceof String s
          && LEGACY_USER.matcher(s).matches()) {
        legacy = s;
      }
    }
    return new CallerIdentity(
        principal == null ? "unknown" : principal, client, legacy == null ? SERVICE_USER : legacy);
  }
}
