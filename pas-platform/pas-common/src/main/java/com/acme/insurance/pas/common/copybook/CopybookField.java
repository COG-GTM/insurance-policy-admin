package com.acme.insurance.pas.common.copybook;

import java.util.Objects;

/**
 * One elementary item of a copybook layout.
 *
 * @param name the COBOL data name (e.g. {@code POLICY-TOTAL-PREMIUM}), kept verbatim for
 *     traceability
 * @param kind storage format
 * @param digits character length for ALPHANUMERIC/FILLER, total digits for numeric kinds
 * @param scale implied decimal places ({@code V9(m)}); 0 for non-numeric kinds
 * @param signed whether the PIC has a leading {@code S}
 */
public record CopybookField(String name, FieldKind kind, int digits, int scale, boolean signed) {

  public CopybookField {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    if (digits <= 0 || scale < 0 || scale > digits) {
      throw new IllegalArgumentException("invalid size for " + name);
    }
  }

  public static CopybookField alphanumeric(String name, int length) {
    return new CopybookField(name, FieldKind.ALPHANUMERIC, length, 0, false);
  }

  public static CopybookField zoned(String name, int digits) {
    return new CopybookField(name, FieldKind.ZONED, digits, 0, false);
  }

  public static CopybookField zoned(String name, int integerDigits, int scale, boolean signed) {
    return new CopybookField(name, FieldKind.ZONED, integerDigits + scale, scale, signed);
  }

  /** {@code PIC S9(integerDigits)V9(scale) COMP-3}. */
  public static CopybookField packed(String name, int integerDigits, int scale) {
    return new CopybookField(name, FieldKind.PACKED, integerDigits + scale, scale, true);
  }

  public static CopybookField date(String name) {
    return new CopybookField(name, FieldKind.DATE_YYYYMMDD, 8, 0, false);
  }

  public static CopybookField filler(int length) {
    return new CopybookField("FILLER", FieldKind.FILLER, length, 0, false);
  }

  /** Bytes the field occupies in the record. */
  public int byteLength() {
    return kind == FieldKind.PACKED ? PackedDecimal.byteLength(digits) : digits;
  }
}
