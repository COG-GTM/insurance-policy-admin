package com.acme.insurance.pas.rating;

/** The rating tables cannot rate a request (missing row, unknown factor set, unsupported value). */
public class RatingDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RatingDataException(String message) {
    super(message);
  }
}
