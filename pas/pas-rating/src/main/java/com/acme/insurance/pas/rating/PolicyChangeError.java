package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.domain.EndorsementType;
import java.util.Optional;

/**
 * Why a renewal (POLRNW) or endorsement (POLEND) is refused. {@link #legacyText()} is the message
 * the CICS program sends, so the API and the parity harness report the same reason; the newer
 * reasons have no legacy equivalent.
 */
public enum PolicyChangeError {
  RENEWAL_REQUEST_INVALID("ERROR RECEIVING RENEWAL DATA"),
  RENEWAL_POLICY_NOT_FOUND("POLICY NOT FOUND FOR RENEWAL"),
  NOT_ELIGIBLE_FOR_RENEWAL("POLICY NOT ELIGIBLE FOR RENEWAL"),
  TOO_MANY_OPEN_CLAIMS("POLICY HAS TOO MANY OPEN CLAIMS FOR RENEWAL"),
  EXPIRY_DATE_CHANGED("POLICY EXPIRY DATE HAS CHANGED"),
  ENDORSEMENT_REQUEST_INVALID("ERROR RECEIVING ENDORSEMENT DATA"),
  ENDORSEMENT_TYPE_REQUIRED("ENDORSEMENT TYPE IS REQUIRED"),
  ENDORSEMENT_TYPE_INVALID("ENDORSEMENT TYPE IS NOT VALID"),
  ENDORSEMENT_POLICY_NOT_FOUND("POLICY NOT FOUND"),
  NOT_ACTIVE_FOR_ENDORSEMENT("ONLY ACTIVE POLICIES CAN BE ENDORSED"),
  POLICY_TERM_INVALID("POLICY TERM IS NOT VALID"),
  PREMIUM_OUT_OF_RANGE("PREMIUM DOES NOT FIT DECIMAL(11,2)");

  private final String legacyText;

  PolicyChangeError(String legacyText) {
    this.legacyText = legacyText;
  }

  public String legacyText() {
    return legacyText;
  }

  /**
   * POLEND requires a type (blank is refused as in POLEND, Q-19) and, unlike POLEND, accepts only
   * the five known codes, so LOW-VALUES or garbage is never written to ENDORSEMENTS.
   */
  public static Optional<PolicyChangeError> checkEndorsementType(String code) {
    if (code == null || code.isBlank()) {
      return Optional.of(ENDORSEMENT_TYPE_REQUIRED);
    }
    return EndorsementType.fromCode(code).isPresent()
        ? Optional.empty()
        : Optional.of(ENDORSEMENT_TYPE_INVALID);
  }
}
