package com.acme.insurance.pas.lifecycle;

/** A renewal or endorsement business rule rejected the request. */
public final class LifecycleRejectedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final Rejection rejection;

  public LifecycleRejectedException(Rejection rejection) {
    super(rejection.message());
    this.rejection = rejection;
  }

  public Rejection rejection() {
    return rejection;
  }

  /** Reason codes; {@link #message()} keeps the legacy screen text where the legacy has one. */
  public enum Rejection {
    POLICY_NOT_ELIGIBLE_FOR_RENEWAL("POLICY NOT ELIGIBLE FOR RENEWAL"),
    UNDERWRITING_DECLINED("DECLINED POLICY CANNOT BE RENEWED"),
    OUTSIDE_RENEWAL_WINDOW("POLICY IS OUTSIDE THE RENEWAL WINDOW"),
    TOO_MANY_OPEN_CLAIMS("TOO MANY OPEN CLAIMS FOR RENEWAL"),
    RENEWAL_COUNT_LIMIT("RENEWAL COUNT LIMIT REACHED"),
    RENEWAL_PREMIUM_INVALID("RENEWAL PREMIUM CANNOT BE CALCULATED"),
    POLICY_NOT_ACTIVE("ONLY ACTIVE POLICIES CAN BE ENDORSED"),
    ENDORSEMENT_TYPE_REQUIRED("ENDORSEMENT TYPE IS REQUIRED"),
    ENDORSEMENT_TYPE_INVALID("INVALID ENDORSEMENT TYPE"),
    ZERO_LENGTH_TERM("POLICY TERM HAS ZERO LENGTH"),
    INVALID_TERM("POLICY TERM IS INVALID"),
    OUTSIDE_TERM("ENDORSEMENT DATE OUTSIDE POLICY TERM"),
    ENDORSEMENT_SEQUENCE_LIMIT("ENDORSEMENT SEQUENCE LIMIT REACHED"),
    PREMIUM_OUT_OF_RANGE("POLICY PREMIUM OUT OF RANGE"),
    ENDORSEMENT_DETAILS_INVALID("ENDORSEMENT DETAILS ARE INVALID");

    private final String message;

    Rejection(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }
}
