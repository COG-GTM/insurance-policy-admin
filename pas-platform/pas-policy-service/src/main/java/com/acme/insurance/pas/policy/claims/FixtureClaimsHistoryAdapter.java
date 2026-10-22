package com.acme.insurance.pas.policy.claims;

import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.domain.claims.ClaimsSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FixtureClaimsHistoryAdapter implements ClaimsHistoryPort {
  private final Map<String, ClaimsFixtureProperties.Fixture> policies = new ConcurrentHashMap<>();

  public FixtureClaimsHistoryAdapter(Map<String, ClaimsFixtureProperties.Fixture> fixtures) {
    policies.putAll(fixtures);
  }

  public void put(String policyNumber, ClaimsFixtureProperties.Fixture fixture) {
    policies.put(policyNumber, fixture);
  }

  @Override
  public int countOpenClaims(String policyNumber) {
    return policies.getOrDefault(policyNumber, empty()).openClaims();
  }

  @Override
  public ClaimsSummary summarizeSince(String policyNumber, LocalDate since) {
    ClaimsFixtureProperties.Fixture fixture = policies.getOrDefault(policyNumber, empty());
    return new ClaimsSummary(fixture.claimCount(), fixture.totalIncurred());
  }

  private static ClaimsFixtureProperties.Fixture empty() {
    return new ClaimsFixtureProperties.Fixture(0, BigDecimal.ZERO.setScale(2), 0);
  }
}
