package com.acme.insurance.pas.policyservice.change;

import java.util.Objects;

/**
 * Who asked for a change, for the audit row.
 *
 * @param principal the token subject
 * @param clientId the OAuth client the token was issued to
 * @param correlationId the request's correlation id (ADR 0001 D8)
 */
public record Actor(String principal, String clientId, String correlationId) {

  public Actor {
    Objects.requireNonNull(principal, "principal");
  }
}
