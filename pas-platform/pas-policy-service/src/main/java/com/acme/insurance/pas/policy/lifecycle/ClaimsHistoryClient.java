package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Fail-closed access to the Claims-owned history (Q-02). Until AB-525's adapter is wired there is
 * no {@link ClaimsHistoryPort} bean and renewals answer 503 rather than skipping the check.
 */
@Component
public class ClaimsHistoryClient {
  private final ObjectProvider<ClaimsHistoryPort> ports;

  public ClaimsHistoryClient(ObjectProvider<ClaimsHistoryPort> ports) {
    this.ports = ports;
  }

  public int countOpenClaims(String policyNumber) {
    ClaimsHistoryPort port = ports.getIfUnique();
    if (port == null) {
      throw new ClaimsUnavailableException("Claims history source is not configured", null);
    }
    try {
      return port.countOpenClaims(policyNumber);
    } catch (RuntimeException exception) {
      throw new ClaimsUnavailableException("Claims history source is unavailable", exception);
    }
  }
}
