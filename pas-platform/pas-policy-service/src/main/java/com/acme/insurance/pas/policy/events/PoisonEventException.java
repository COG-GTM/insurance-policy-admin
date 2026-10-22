package com.acme.insurance.pas.policy.events;

public final class PoisonEventException extends RuntimeException {
  public PoisonEventException(String message) {
    super(message);
  }

  public PoisonEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
