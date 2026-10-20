package com.acme.insurance.pas.policy.inquiry;

/** Mirrors the facade's {@code rs.getString(..).trim()} / {@code rs.getInt(..)} mapping. */
final class Trim {

  private Trim() {}

  static String of(String value) {
    return value == null ? null : value.trim();
  }

  /** JDBC {@code getInt} returns 0 for SQL NULL; the facade beans use primitive ints. */
  static int primitive(Number value) {
    return value == null ? 0 : value.intValue();
  }
}
