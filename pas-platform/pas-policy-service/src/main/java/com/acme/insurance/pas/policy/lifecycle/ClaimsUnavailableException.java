package com.acme.insurance.pas.policy.lifecycle;

/** The claims history source could not answer; renewal fails closed (Q-02). */
public final class ClaimsUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ClaimsUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
