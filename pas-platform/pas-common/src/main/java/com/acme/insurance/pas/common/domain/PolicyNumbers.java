package com.acme.insurance.pas.common.domain;

/**
 * Policy-number formatting as produced by POLNEW: {@code STRING 'POL' WS-POLICY-SEQ DELIMITED BY
 * SIZE INTO POLICY-NUMBER}, where {@code WS-POLICY-SEQ} is {@code PIC 9(10)} and {@code
 * POLICY-NUMBER} is {@code PIC X(12)}. The 13 source characters overflow the 12-byte receiver, and
 * without {@code ON OVERFLOW} COBOL silently drops the last digit. Sequence values 1000000..1000009
 * therefore all format to {@code POL000100000}. This is reproduced at parity here; whether to fix
 * it is an open question (see docs/modernization/open-questions.md).
 */
public final class PolicyNumbers {

  public static final String PREFIX = "POL";
  public static final int LENGTH = 12;
  private static final int SEQ_DIGITS = 10;

  private PolicyNumbers() {}

  /** Exactly the 12-character value POLNEW writes for the given POLICY_SEQ value. */
  public static String fromSequence(long sequenceValue) {
    if (sequenceValue < 0 || sequenceValue > 9_999_999_999L) {
      throw new IllegalArgumentException("sequence value out of PIC 9(10) range: " + sequenceValue);
    }
    String digits = String.format("%0" + SEQ_DIGITS + "d", sequenceValue);
    return (PREFIX + digits).substring(0, LENGTH);
  }
}
