package com.acme.insurance.pas.policy.integration;

/** A record no retry can fix (not a v1 envelope, unknown source); dead-lettered immediately. */
public class PoisonMessageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PoisonMessageException(String message) {
    super(message);
  }

  public PoisonMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
