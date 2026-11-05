package com.acme.insurance.pas.policyservice.change;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Who asked for a change, for the audit row.
 *
 * @param principal the token subject; one longer than the audit column is cut and suffixed with its
 *     SHA-256, so it still fits and stays distinguishable
 * @param clientId the OAuth client the token was issued to
 * @param correlationId the request's correlation id (ADR 0001 D8)
 */
public record Actor(String principal, String clientId, String correlationId) {

  /** PASAPP.POLICY_CHANGE_AUDIT.PRINCIPAL is VARCHAR(255); DB2 counts bytes, so this is UTF-8. */
  static final int PRINCIPAL_BYTES = 255;

  private static final String DIGEST_MARK = "#sha256:";

  public Actor {
    Objects.requireNonNull(principal, "principal");
    principal = auditPrincipal(principal);
  }

  static String auditPrincipal(String principal) {
    if (utf8Length(principal) <= PRINCIPAL_BYTES) {
      return principal;
    }
    String suffix = DIGEST_MARK + HexFormat.of().formatHex(sha256(principal));
    int budget = PRINCIPAL_BYTES - suffix.length();
    StringBuilder prefix = new StringBuilder();
    int used = 0;
    for (int i = 0; i < principal.length(); ) {
      int cp = principal.codePointAt(i);
      int bytes = utf8Length(new String(Character.toChars(cp)));
      if (used + bytes > budget) {
        break;
      }
      prefix.appendCodePoint(cp);
      used += bytes;
      i += Character.charCount(cp);
    }
    return prefix + suffix;
  }

  private static int utf8Length(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JRE", e);
    }
  }
}
