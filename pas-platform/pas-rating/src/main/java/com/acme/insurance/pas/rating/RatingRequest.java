package com.acme.insurance.pas.rating;

import java.time.LocalDate;
import java.util.Objects;

public record RatingRequest(
    String policyType, LocalDate ratingDate, String territoryCode, String classCode) {
  public RatingRequest {
    policyType = normalizePolicyType(policyType);
    Objects.requireNonNull(ratingDate, "ratingDate is required");
  }

  static String normalizePolicyType(String policyType) {
    String value = policyType == null ? "" : policyType;
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    value = value.substring(0, end);
    if (value.length() > 3) {
      throw new IllegalArgumentException("policyType must be at most three characters");
    }
    return value;
  }
}
