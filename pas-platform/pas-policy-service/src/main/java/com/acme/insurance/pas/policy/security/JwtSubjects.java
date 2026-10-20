package com.acme.insurance.pas.policy.security;

/**
 * Bounds on the JWT {@code sub} claim where write APIs persist it ({@code
 * pas.underwriting_decisions.underwriter_id/override_by}, {@code
 * pas.idempotency_keys.caller_subject}, all {@code VARCHAR(255)}).
 */
public final class JwtSubjects {

  public static final int MAX_LENGTH = 255;

  private JwtSubjects() {}

  /**
   * Returns {@code subject}, or throws {@link NotEntitledException} (403) when it is longer than
   * {@link #MAX_LENGTH}, so an oversized token subject is rejected instead of failing the insert.
   */
  public static String checkLength(String subject) {
    if (subject != null && subject.length() > MAX_LENGTH) {
      throw new NotEntitledException("token subject is longer than " + MAX_LENGTH + " characters");
    }
    return subject;
  }
}
