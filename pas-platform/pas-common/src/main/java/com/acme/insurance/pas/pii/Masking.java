package com.acme.insurance.pas.pii;

public final class Masking {
  private Masking() {}

  public static String redact(Object value) {
    return value == null ? null : "****";
  }

  public static String last(String value, int count) {
    if (value == null) {
      return null;
    }
    if (count < 0) {
      throw new IllegalArgumentException("Visible character count cannot be negative");
    }
    int visible = Math.min(count, value.length());
    return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
  }
}
