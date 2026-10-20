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
}
