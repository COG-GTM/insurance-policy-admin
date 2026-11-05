package com.acme.insurance.pas.policyservice.inquiry;

/**
 * Outcome of one inquiry. {@link Outcome#DENIED} and {@link Outcome#NOT_FOUND} are reported to the
 * caller identically, so the API does not reveal which policy numbers exist.
 */
public record InquiryResult<T>(Outcome outcome, T value) {

  public enum Outcome {
    FOUND,
    NOT_FOUND,
    DENIED
  }

  static <T> InquiryResult<T> found(T value) {
    return new InquiryResult<>(Outcome.FOUND, value);
  }

  static <T> InquiryResult<T> notFound() {
    return new InquiryResult<>(Outcome.NOT_FOUND, null);
  }

  static <T> InquiryResult<T> denied() {
    return new InquiryResult<>(Outcome.DENIED, null);
  }
}
