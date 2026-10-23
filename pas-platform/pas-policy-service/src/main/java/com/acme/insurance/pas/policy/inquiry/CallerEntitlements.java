package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.common.security.PasClaims;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.security.oauth2.jwt.Jwt;

public record CallerEntitlements(boolean wholeBook, String agentId, List<String> branchCodes) {
  public CallerEntitlements {
    branchCodes = List.copyOf(branchCodes);
  }

  public static CallerEntitlements from(Jwt jwt) {
    String book = normalize(jwt.getClaimAsString(PasClaims.BOOK));
    String agent = normalize(jwt.getClaimAsString(PasClaims.AGENT_ID));
    Object rawBranches = jwt.getClaims().get(PasClaims.BRANCH_CODES);
    List<String> branches = new ArrayList<>();
    if (rawBranches instanceof List<?> values) {
      for (Object value : values) {
        if (value instanceof String code) {
          String normalized = normalize(code);
          if (normalized != null) {
            branches.add(normalized);
          }
        }
      }
    }
    return new CallerEntitlements(PasClaims.BOOK_ALL.equals(book), agent, branches);
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
