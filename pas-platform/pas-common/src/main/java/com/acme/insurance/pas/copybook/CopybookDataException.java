package com.acme.insurance.pas.copybook;

public final class CopybookDataException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String fieldName;
  private final int offset;

  public CopybookDataException(String fieldName, int offset, String reason) {
    super("Invalid copybook data for " + fieldName + " at offset " + offset + ": " + reason);
    this.fieldName = fieldName;
    this.offset = offset;
  }

  public String fieldName() {
    return fieldName;
  }

  public int offset() {
    return offset;
  }
}
