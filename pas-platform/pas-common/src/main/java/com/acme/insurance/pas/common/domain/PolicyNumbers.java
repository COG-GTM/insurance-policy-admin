package com.acme.insurance.pas.common.domain;

import java.util.Locale;

/**
 * Policy-number formatting as produced by POLNEW: {@code STRING 'POL' WS-POLICY-SEQ DELIMITED BY
 * SIZE INTO POLICY-NUMBER}, where {@code WS-POLICY-SEQ} is {@code PIC 9(10)} and {@code
 * POLICY-NUMBER} is {@code PIC X(12)}. The 13 source characters overflow the 12-byte receiver, and
 * without {@code ON OVERFLOW} COBOL silently drops the last digit. Sequence values 1000000..1000009
 * therefore all format to {@code POL000100000}. {@link #fromSequence} reproduces that at parity
 * (parity harness, migrated data); new business uses {@link #forNewBusiness}, the quirk register
 * Q-27 fix that keeps the 12-character {@code POL} + 9-digit format without the truncation.
 */
public final class PolicyNumbers {

  public static final String PREFIX = "POL";
  public static final int LENGTH = 12;
  private static final int SEQ_DIGITS = 10;
  private static final int NEW_BUSINESS_DIGITS = LENGTH - PREFIX.length();
  private static final long NEW_BUSINESS_MAX = 999_999_999L;

  private PolicyNumbers() {}

  /** Exactly the 12-character value POLNEW writes for the given POLICY_SEQ value. */
  public static String fromSequence(long sequenceValue) {
    if (sequenceValue < 0 || sequenceValue > 9_999_999_999L) {
      throw new IllegalArgumentException("sequence value out of PIC 9(10) range: " + sequenceValue);
    }
    String digits = String.format(Locale.ROOT, "%0" + SEQ_DIGITS + "d", sequenceValue);
    return (PREFIX + digits).substring(0, LENGTH);
  }

  /**
   * Q-27 fix (docs/modernization/quirk-register.md#q-27): {@code POL} + the sequence value
   * zero-padded to 9 digits, so every sequence value maps to its own number. Values at or above the
   * migrated DB2 sequence position cannot equal a migrated {@code POL} + floor(seq / 10) number,
   * because floor(s / 10) &lt; s.
   */
  public static String forNewBusiness(long sequenceValue) {
    if (sequenceValue < 0 || sequenceValue > NEW_BUSINESS_MAX) {
      throw new IllegalArgumentException(
          "sequence value outside the 9-digit policy number space: " + sequenceValue);
    }
    return PREFIX + String.format(Locale.ROOT, "%0" + NEW_BUSINESS_DIGITS + "d", sequenceValue);
  }
}
