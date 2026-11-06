package com.acme.insurance.pas.parity.adapters;

/** COBOL data semantics the adapters need to mirror host variables. */
final class Cobol {

  private Cobol() {}

  /** MOVE to PIC X(length): truncated on the right, padded with spaces. */
  static String alphanumeric(String value, int length) {
    String padded = value + " ".repeat(Math.max(0, length - value.length()));
    return padded.substring(0, length);
  }
}
