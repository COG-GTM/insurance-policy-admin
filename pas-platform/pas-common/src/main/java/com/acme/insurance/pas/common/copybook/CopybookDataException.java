package com.acme.insurance.pas.common.copybook;

/** Raised when a fixed-width record or field cannot be encoded or decoded without data loss. */
public class CopybookDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CopybookDataException(String message) {
    super(message);
  }

  public CopybookDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
