package com.acme.insurance.pas.policy.lifecycle;

/** A create-policy request failed a business rule (422); {@code code} is stable for clients. */
public class NewBusinessRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String POLNEW_VALIDATION = "POLNEW_VALIDATION";
  public static final String INVALID_TERM = "INVALID_TERM";
  public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

  private final String code;

  public NewBusinessRejectedException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
