package com.acme.insurance.pas.policy.claims;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.claims.fixture")
public record ClaimsFixtureProperties(Map<String, Fixture> policies) {
  public ClaimsFixtureProperties {
    policies = policies == null ? Map.of() : Map.copyOf(policies);
  }

  public record Fixture(int claimCount, BigDecimal totalIncurred, int openClaims) {
    public Fixture {
      totalIncurred = totalIncurred == null ? BigDecimal.ZERO.setScale(2) : totalIncurred;
    }
  }
}
