package com.acme.insurance.pas.policyservice.security;

import org.springframework.security.core.Authentication;

/**
 * Resolves the caller's book-of-business entitlement. The default reads token claims; an
 * entitlement store owned by PAS can replace it if the IdP cannot issue the claims (ADR 0001 risk
 * register).
 */
public interface EntitlementResolver {

  Entitlement resolve(Authentication authentication);
}
