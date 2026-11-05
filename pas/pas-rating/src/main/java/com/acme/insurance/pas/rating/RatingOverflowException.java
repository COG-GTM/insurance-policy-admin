package com.acme.insurance.pas.rating;

/**
 * A rating step does not fit its COBOL receiving field. PREMBAT has no {@code ON SIZE ERROR}, so
 * the COBOL would silently drop the high-order digits; the Java component refuses instead (quirk
 * Q-522-1).
 */
public class RatingOverflowException extends ArithmeticException {

  private static final long serialVersionUID = 1L;

  public RatingOverflowException(String message) {
    super(message);
  }
}
