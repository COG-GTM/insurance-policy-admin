package com.acme.insurance.pas.common.copybook;

/** Storage formats supported by {@link RecordLayout}. */
public enum FieldKind {
  /** {@code PIC X(n)}: text, right-padded with spaces. Decodes with trailing spaces removed. */
  ALPHANUMERIC,
  /** {@code PIC [S]9(n)[V9(m)]} DISPLAY. Decodes to {@link java.math.BigDecimal}. */
  ZONED,
  /** {@code PIC [S]9(n)[V9(m)] COMP-3}. Decodes to {@link java.math.BigDecimal}. */
  PACKED,
  /**
   * {@code PIC 9(08)} holding YYYYMMDD. Decodes to {@link java.time.LocalDate}; all zeros or all
   * spaces decode to {@code null}, and {@code null} encodes as zeros.
   */
  DATE_YYYYMMDD,
  /** {@code FILLER}: ignored on decode, written as spaces. */
  FILLER
}
