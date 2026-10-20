package com.acme.insurance.pas.policy.events;

/**
 * Event types written by pas-policy-service. They replace the CICS TDQ writes: POLNEW's {@code
 * ACME.PAS.UNDERWRITING.REQUEST} becomes {@link #POLICY_CREATED}; UNDWRT's {@code
 * ACME.PAS.UNDERWRITING.DECISION} becomes {@link #UNDERWRITING_REFERRED} (RS/RM) or {@link
 * #UNDERWRITING_DECIDED} (AP/DC). Catalogue: docs/modernization/events.md.
 */
public final class PasEventTypes {

  public static final int SCHEMA_VERSION = 1;

  public static final String POLICY_CREATED = "PolicyCreated";
  public static final String UNDERWRITING_DECIDED = "UnderwritingDecided";
  public static final String UNDERWRITING_REFERRED = "UnderwritingReferred";

  private PasEventTypes() {}
}
