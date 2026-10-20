package com.acme.insurance.pas.rating;

/**
 * An intermediate or final amount does not fit the COBOL PIC it is stored in. PREMBAT has no ON
 * SIZE ERROR and would silently drop high-order digits; with the parity tables that can never
 * happen (largest premium 5000.00 x 1.0000), so the Java port fails loudly instead of truncating.
 */
public class RatingOverflowException extends ArithmeticException {

  private static final long serialVersionUID = 1L;

  public RatingOverflowException(String message) {
    super(message);
  }
}
