package com.acme.insurance.pas.policy.lifecycle;

/** The capability's route flag is LEGACY, so this service does not take the traffic (503). */
public final class RouteNotServingException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public RouteNotServingException(String capability) {
    super("Route " + capability + " is not serving traffic");
  }
}
