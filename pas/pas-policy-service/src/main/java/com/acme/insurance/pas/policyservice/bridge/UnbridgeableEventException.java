package com.acme.insurance.pas.policyservice.bridge;

/** An event that can never be bridged (bad schema, unknown policy); goes to the DLT unretried. */
public class UnbridgeableEventException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnbridgeableEventException(String message) {
    super(message);
  }
}
