package com.acme.insurance.pas.rating;

/** The renewal premium cannot be calculated without COBOL wrap or division by zero (Q-06, Q-47). */
public final class RenewalPremiumException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public RenewalPremiumException(String message, Throwable cause) {
    super(message, cause);
  }

  public RenewalPremiumException(String message) {
    super(message);
  }
}
