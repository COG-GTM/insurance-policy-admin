package com.acme.insurance.pas.rating;

public final class PremiumOverflowException extends ArithmeticException {
  private static final long serialVersionUID = 1L;

  private final String field;
  private final String picture;

  public PremiumOverflowException(String field, String picture) {
    super(field + " exceeds " + picture);
    this.field = field;
    this.picture = picture;
  }

  public String field() {
    return field;
  }

  public String picture() {
    return picture;
  }
}
