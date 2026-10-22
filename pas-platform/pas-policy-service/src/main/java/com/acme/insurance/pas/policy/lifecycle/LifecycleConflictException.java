package com.acme.insurance.pas.policy.lifecycle;

/** The request conflicts with the policy's current state (HTTP 409). */
public final class LifecycleConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public LifecycleConflictException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
