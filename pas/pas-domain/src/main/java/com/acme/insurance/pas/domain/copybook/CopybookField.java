package com.acme.insurance.pas.domain.copybook;

/** An elementary copybook item. Group items carry no storage and are not modelled. */
public sealed interface CopybookField
    permits CopybookField.Alphanumeric,
        CopybookField.Display,
        CopybookField.Packed,
        CopybookField.Filler {

  String name();

  /** Bytes the item occupies in the record. */
  int length();

  /** {@code PIC X(n)}. */
  record Alphanumeric(String name, int length) implements CopybookField {}

  /** Unsigned zoned decimal {@code PIC 9(n)}, also used for YYYYMMDD dates. */
  record Display(String name, int digits) implements CopybookField {
    @Override
    public int length() {
      return digits;
    }
  }

  /** Signed packed decimal {@code PIC S9(i)V9(s) COMP-3}. */
  record Packed(String name, int integerDigits, int scale) implements CopybookField {
    @Override
    public int length() {
      return PackedDecimal.byteLength(integerDigits + scale);
    }
  }

  /** {@code FILLER PIC X(n)}: written as spaces, ignored on read. */
  record Filler(int length) implements CopybookField {
    @Override
    public String name() {
      return "FILLER";
    }
  }

  static Alphanumeric x(String name, int length) {
    return new Alphanumeric(name, length);
  }

  static Display nine(String name, int digits) {
    return new Display(name, digits);
  }

  static Packed comp3(String name, int integerDigits, int scale) {
    return new Packed(name, integerDigits, scale);
  }

  static Filler filler(int length) {
    return new Filler(length);
  }
}
