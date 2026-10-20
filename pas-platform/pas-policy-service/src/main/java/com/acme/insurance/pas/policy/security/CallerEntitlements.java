package com.acme.insurance.pas.policy.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Object-level entitlements carried in the caller's JWT (ADR 0001 D5).
 *
 * <ul>
 *   <li>{@code agent_code}: the caller may see policies whose {@code AGENT_CODE} matches.
 *   <li>{@code branch_code}: the caller may see policies whose {@code BRANCH_CODE} matches.
 *   <li>{@code book}: book-of-business entries, each {@code *} (whole book, written {@code book:*}
 *       in the ADR), {@code agent:<code>} or {@code branch:<code>}.
 * </ul>
 *
 * Each claim is an independent grant: the IdP issues only the claims the caller is entitled to. A
 * caller with none of them sees no policy. Codes compare trimmed and case-insensitive, because the
 * DB2 columns are blank-padded CHAR.
 */
public record CallerEntitlements(String agentCode, String branchCode, Set<String> book) {

  public static final String CLAIM_AGENT_CODE = "agent_code";
  public static final String CLAIM_BRANCH_CODE = "branch_code";
  public static final String CLAIM_BOOK = "book";
  public static final String WHOLE_BOOK = "*";

  public CallerEntitlements {
    agentCode = normalise(agentCode);
    branchCode = normalise(branchCode);
    book =
        book == null
            ? Set.of()
            : book.stream()
                .map(CallerEntitlements::normaliseBookEntry)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Set<String> book() {
    return Set.copyOf(book);
  }

  public static CallerEntitlements fromJwt(Jwt jwt) {
    return new CallerEntitlements(
        jwt.getClaimAsString(CLAIM_AGENT_CODE),
        jwt.getClaimAsString(CLAIM_BRANCH_CODE),
        Set.copyOf(bookClaim(jwt.getClaim(CLAIM_BOOK))));
  }

  /**
   * True when the caller may see a policy written by {@code policyAgent} in {@code policyBranch}.
   */
  public boolean canAccess(String policyAgent, String policyBranch) {
    if (book.contains(WHOLE_BOOK)) {
      return true;
    }
    String agent = normalise(policyAgent);
    String branch = normalise(policyBranch);
    return (agent != null && (agent.equals(agentCode) || book.contains("agent:" + agent)))
        || (branch != null && (branch.equals(branchCode) || book.contains("branch:" + branch)));
  }

  /** True when the caller holds the whole book ({@code book:*}). */
  public boolean wholeBook() {
    return book.contains(WHOLE_BOOK);
  }

  /** Agent codes {@link #canAccess} grants: the caller's own plus {@code agent:<code>} entries. */
  public Set<String> agentScope() {
    return scope(agentCode, "agent:");
  }

  /**
   * Branch codes {@link #canAccess} grants: the caller's own plus {@code branch:<code>} entries.
   */
  public Set<String> branchScope() {
    return scope(branchCode, "branch:");
  }

  private Set<String> scope(String own, String prefix) {
    Set<String> codes = new java.util.TreeSet<>();
    if (own != null) {
      codes.add(own);
    }
    book.stream()
        .filter(e -> e.startsWith(prefix))
        .forEach(e -> codes.add(e.substring(prefix.length())));
    return Set.copyOf(codes);
  }

  private static Collection<String> bookClaim(Object claim) {
    if (claim instanceof Collection<?> values) {
      return values.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }
    if (claim instanceof String value) {
      return Stream.of(value.split("[\\s,]+")).filter(s -> !s.isEmpty()).toList();
    }
    return List.of();
  }

  private static String normaliseBookEntry(String entry) {
    String value = normalise(entry);
    if (value == null) {
      return null;
    }
    if (WHOLE_BOOK.equals(value) || "BOOK:*".equals(value)) {
      return WHOLE_BOOK;
    }
    int colon = value.indexOf(':');
    if (colon <= 0 || colon == value.length() - 1) {
      return null;
    }
    String kind = value.substring(0, colon);
    String code = normalise(value.substring(colon + 1));
    return ("AGENT".equals(kind) || "BRANCH".equals(kind)) && code != null
        ? kind.toLowerCase(Locale.ROOT) + ":" + code
        : null;
  }

  private static String normalise(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip().toUpperCase(Locale.ROOT);
  }
}
