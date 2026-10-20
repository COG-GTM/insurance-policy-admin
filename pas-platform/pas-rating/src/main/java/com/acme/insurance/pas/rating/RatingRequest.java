package com.acme.insurance.pas.rating;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Inputs to one premium calculation.
 *
 * @param policyType POLICY-TYPE (PIC X(03)); matched exactly like the COBOL 88-levels, so lowercase
 *     or leading-blank values fall to the OTHER base rate
 * @param territoryCode rating territory (PIC X(06)); blank/null means "not known", which only the
 *     {@code *} catch-all row can rate
 * @param classCode rating class (PIC X(05)); same rules as the territory code
 * @param termEffectiveDate term start, passed through to the breakdown
 * @param termExpiryDate term end, passed through to the breakdown
 */
public record RatingRequest(
    String policyType,
    String territoryCode,
    String classCode,
    LocalDate termEffectiveDate,
    LocalDate termExpiryDate) {

  public RatingRequest {
    policyType = policyType == null ? "" : policyType;
    Objects.requireNonNull(termEffectiveDate, "termEffectiveDate");
    Objects.requireNonNull(termExpiryDate, "termExpiryDate");
  }

  /** PREMBAT's inputs only: the batch program never reads a territory or class code. */
  public static RatingRequest of(String policyType, LocalDate effective, LocalDate expiry) {
    return new RatingRequest(policyType, null, null, effective, expiry);
  }
}
