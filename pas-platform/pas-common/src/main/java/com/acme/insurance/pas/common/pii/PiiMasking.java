package com.acme.insurance.pas.common.pii;

import java.time.LocalDate;

/** Display masking for regulated fields. Use these whenever PII leaves the service boundary. */
public final class PiiMasking {

  public static final String REDACTED = "[REDACTED]";

  private PiiMasking() {}

  /** {@code ***-**-1234} style: SSN last 4 is itself sensitive, so only the last digit shows. */
  public static String ssnLast4(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String v = value.strip();
    return "***" + v.charAt(v.length() - 1);
  }

  /** Tax id keeps its last 2 characters. */
  public static String taxId(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String v = value.strip();
    int keep = Math.min(2, v.length() - 1);
    return "*".repeat(v.length() - keep) + v.substring(v.length() - keep);
  }

  /** Date of birth shows the year only. */
  public static String dateOfBirth(LocalDate value) {
    return value == null ? null : value.getYear() + "-**-**";
  }

  /** Credit scores are never displayed. */
  public static String creditScore(Integer value) {
    return value == null ? null : REDACTED;
  }

  /** Initial only, e.g. {@code "Thomas"} → {@code "T***"}. */
  public static String name(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String v = value.strip();
    return v.charAt(0) + "***";
  }

  /** Last four digits only, e.g. {@code "312-555-0101"} → {@code "***-***-0101"}. */
  public static String phone(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String digits = value.replaceAll("\\D", "");
    return digits.length() <= 4 ? REDACTED : "***-***-" + digits.substring(digits.length() - 4);
  }

  /** First character of the local part plus domain, e.g. {@code "t***@example.com"}. */
  public static String email(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String v = value.strip();
    int at = v.indexOf('@');
    return at <= 0 ? REDACTED : v.charAt(0) + "***" + v.substring(at);
  }

  /** First three characters (US ZIP3), e.g. {@code "60601"} → {@code "606**"}. */
  public static String postalCode(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String v = value.strip();
    return v.length() <= 3 ? REDACTED : v.substring(0, 3) + "*".repeat(v.length() - 3);
  }

  /** Fully redacted (street address and other free text). */
  public static String redact(String value) {
    return value == null || value.isBlank() ? value : REDACTED;
  }
}
