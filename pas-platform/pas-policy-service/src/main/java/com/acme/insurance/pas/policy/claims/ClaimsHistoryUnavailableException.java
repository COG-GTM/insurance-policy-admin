package com.acme.insurance.pas.policy.claims;

public class ClaimsHistoryUnavailableException extends RuntimeException {
  public ClaimsHistoryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public ClaimsHistoryUnavailableException(String message) {
    super(message);
  }
}
