package com.acme.insurance.pas.policy.newbusiness;

import java.util.Locale;

public final class PolicyNumbers {
  private static final long MAX_SEQUENCE = 999_999_999L;

  private PolicyNumbers() {}

  public static String modern(long sequence) {
    if (sequence < 0 || sequence > MAX_SEQUENCE) {
      throw new IllegalArgumentException("Policy sequence is exhausted");
    }
    return "POL" + String.format(Locale.ROOT, "%09d", sequence);
  }

  public static String legacy(long sequence) {
    if (sequence < 0 || sequence > 9_999_999_999L) {
      throw new IllegalArgumentException("Legacy policy sequence is out of range");
    }
    String padded = String.format(Locale.ROOT, "%010d", sequence);
    return "POL" + padded.substring(0, 9);
  }
}
