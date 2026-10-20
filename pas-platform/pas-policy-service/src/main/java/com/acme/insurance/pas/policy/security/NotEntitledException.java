package com.acme.insurance.pas.policy.security;

/**
 * The caller may not act on the agent/branch it named (write requests). Existing objects the caller
 * cannot see are reported as 404 instead; this is only for objects the request itself describes.
 */
public class NotEntitledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotEntitledException(String message) {
    super(message);
  }
}
