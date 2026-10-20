package com.acme.insurance.pas.policy.lifecycle;

/** A renewal or endorsement the legacy program would refuse; mapped to 422 with the message. */
public class PolicyChangeRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String RENEWAL_NOT_ELIGIBLE = "RENEWAL_NOT_ELIGIBLE";
  public static final String RENEWAL_TERM_INVALID = "RENEWAL_TERM_INVALID";
  public static final String ENDORSEMENT_INVALID = "ENDORSEMENT_INVALID";

  private final String code;

  public PolicyChangeRejectedException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
