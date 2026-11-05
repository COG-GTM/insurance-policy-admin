package com.acme.insurance.pas.policyservice.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token validation settings (ADR 0001 D5). The issuer and audience are mandatory; the
 * signing keys come from the IdP's JWK set, or from a PEM public key for local stacks and tests.
 *
 * @param issuer expected {@code iss} claim
 * @param audience expected {@code aud} value
 * @param jwkSetUri the IdP's JWK set endpoint
 * @param publicKeyLocation PEM RSA public key, used when no JWK set URI is configured
 * @param clockSkew allowed clock skew for {@code exp} and {@code nbf}
 */
@ConfigurationProperties("pas.security.jwt")
public record PasSecurityProperties(
    String issuer,
    String audience,
    String jwkSetUri,
    String publicKeyLocation,
    Duration clockSkew) {

  public PasSecurityProperties {
    clockSkew = clockSkew == null ? Duration.ofSeconds(60) : clockSkew;
  }
}
