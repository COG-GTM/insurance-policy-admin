package com.acme.insurance.pas.copybook;

public record FieldDescriptor(String name, int offset, FieldType type) {
  public FieldDescriptor {
    if (name == null || name.isBlank() || offset < 0 || type == null) {
      throw new IllegalArgumentException("Invalid field descriptor");
    }
  }
}
