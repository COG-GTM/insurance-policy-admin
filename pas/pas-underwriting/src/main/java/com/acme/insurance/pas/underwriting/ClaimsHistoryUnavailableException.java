package com.acme.insurance.pas.underwriting;

/** The claims interface did not answer; no decision may be taken without the loss history. */
public class ClaimsHistoryUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ClaimsHistoryUnavailableException(String message) {
    super(message);
  }

  public ClaimsHistoryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
