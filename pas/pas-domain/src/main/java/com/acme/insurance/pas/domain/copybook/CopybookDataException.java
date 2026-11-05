package com.acme.insurance.pas.domain.copybook;

/**
 * Raised when record bytes or a value cannot be represented in a copybook field. Messages name the
 * field and never include the field content, so PII cannot leak through them.
 */
public class CopybookDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CopybookDataException(String message) {
    super(message);
  }

  public CopybookDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
