package com.acme.insurance.pas.copybook;

public sealed interface FieldType
    permits FieldType.Alphanumeric,
        FieldType.UnsignedDisplay,
        FieldType.SignedDisplay,
        FieldType.PackedDecimal,
        FieldType.DateYyyyMmDd,
        FieldType.Filler {
  int byteLength();

  record Alphanumeric(int length) implements FieldType {
    public Alphanumeric {
      requirePositive(length);
    }

    @Override
    public int byteLength() {
      return length;
    }
  }

  record UnsignedDisplay(int digits, int scale) implements FieldType {
    public UnsignedDisplay {
      requireNumeric(digits, scale);
    }

    @Override
    public int byteLength() {
      return digits;
    }
  }

  record SignedDisplay(int digits, int scale) implements FieldType {
    public SignedDisplay {
      requireNumeric(digits, scale);
    }

    @Override
    public int byteLength() {
      return digits;
    }
  }

  record PackedDecimal(int digits, int scale, boolean signed) implements FieldType {
    public PackedDecimal {
      requireNumeric(digits, scale);
    }

    @Override
    public int byteLength() {
      return digits / 2 + 1;
    }
  }

  record DateYyyyMmDd() implements FieldType {
    @Override
    public int byteLength() {
      return 8;
    }
  }

  record Filler(int length) implements FieldType {
    public Filler {
      requirePositive(length);
    }

    @Override
    public int byteLength() {
      return length;
    }
  }

  private static void requirePositive(int value) {
    if (value < 1) {
      throw new IllegalArgumentException("Field length must be positive");
    }
  }

  private static void requireNumeric(int digits, int scale) {
    if (digits < 1 || scale < 0 || scale > digits) {
      throw new IllegalArgumentException("Invalid numeric field dimensions");
    }
  }
}
