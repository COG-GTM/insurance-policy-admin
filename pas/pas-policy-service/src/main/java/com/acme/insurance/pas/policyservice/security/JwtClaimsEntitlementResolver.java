package com.acme.insurance.pas.policyservice.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Reads the entitlement from access-token claims: {@value #BOOK_CLAIM} set to {@value #WHOLE_BOOK}
 * grants the whole book, {@value #AGENTS_CLAIM} and {@value #BRANCHES_CLAIM} list agent and branch
 * codes. The claims may be JSON arrays or space-separated strings. Unknown or malformed claims
 * grant nothing.
 */
@Component
public class JwtClaimsEntitlementResolver implements EntitlementResolver {

  public static final String BOOK_CLAIM = "pas_book";
  public static final String WHOLE_BOOK = "*";
  public static final String AGENTS_CLAIM = "pas_agents";
  public static final String BRANCHES_CLAIM = "pas_branches";

  @Override
  public Entitlement resolve(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      return Entitlement.NONE;
    }
    Jwt jwt = token.getToken();
    boolean wholeBook = WHOLE_BOOK.equals(jwt.getClaims().get(BOOK_CLAIM));
    return new Entitlement(wholeBook, codes(jwt, AGENTS_CLAIM), codes(jwt, BRANCHES_CLAIM));
  }

  private static Set<String> codes(Jwt jwt, String claim) {
    Object value = jwt.getClaims().get(claim);
    Collection<?> raw;
    if (value instanceof Collection<?> list) {
      raw = list;
    } else if (value instanceof String text) {
      raw = List.of(text.trim().split("\\s+"));
    } else {
      return Set.of();
    }
    Set<String> codes = new LinkedHashSet<>();
    for (Object code : raw) {
      if (code instanceof String s && !s.isBlank() && !WHOLE_BOOK.equals(s.trim())) {
        codes.add(s.trim());
      }
    }
    return codes;
  }
}
