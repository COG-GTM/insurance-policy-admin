package com.acme.insurance.pas.rating;

/** The reference data needed to rate is missing or inconsistent; nothing is rated. */
public class RatingDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RatingDataException(String message) {
    super(message);
  }
}
