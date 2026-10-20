package com.acme.insurance.pas.policy.api;

/** A malformed request the bean validation annotations cannot express (400). */
public class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidRequestException(String message) {
    super(message);
  }
}
