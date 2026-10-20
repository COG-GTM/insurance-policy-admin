package com.acme.insurance.pas.policy.claims;

/** The claims source could not answer; the caller must fail the request (no silent zero). */
public class ClaimsHistoryUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ClaimsHistoryUnavailableException(String message) {
    super(message);
  }

  public ClaimsHistoryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
