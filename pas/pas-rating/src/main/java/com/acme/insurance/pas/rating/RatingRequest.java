package com.acme.insurance.pas.rating;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What one premium is rated from. {@code policyType} is POLICIES.POLICY_TYPE as stored; {@code
 * ratingDate} selects the reference rows in force (PREMBAT rates with the run date). {@code
 * territoryCode} and {@code classCode} are only read by table-driven factors and may be null.
 */
public record RatingRequest(
    String policyType, LocalDate ratingDate, String territoryCode, String classCode) {

  public RatingRequest {
    Objects.requireNonNull(policyType, "policyType");
    Objects.requireNonNull(ratingDate, "ratingDate");
  }

  /** A request as PREMBAT makes it today: policy type and run date only. */
  public static RatingRequest of(String policyType, LocalDate ratingDate) {
    return new RatingRequest(policyType, ratingDate, null, null);
  }
}
