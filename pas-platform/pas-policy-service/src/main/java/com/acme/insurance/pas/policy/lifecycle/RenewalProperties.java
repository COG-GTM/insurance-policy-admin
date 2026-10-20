package com.acme.insurance.pas.policy.lifecycle;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Renewal configuration ({@code pas.renewal.*}).
 *
 * @param upliftPct standard renewal increase in percent (POLRNW literal 1.05; quirk register Q-05)
 * @param rateCapPct flat rate-increase cap in percent (POLRNW {@code WS-RATE-INCREASE-CAP}, Q-04)
 * @param stateRateCapPct per-state caps keyed by state code; empty until state caps are filed
 * @param maxOpenClaims renewal is refused when the policy has more open claims than this (Q-02)
 */
@ConfigurationProperties("pas.renewal")
public record RenewalProperties(
    BigDecimal upliftPct,
    BigDecimal rateCapPct,
    Map<String, BigDecimal> stateRateCapPct,
    int maxOpenClaims) {

  public RenewalProperties {
    Objects.requireNonNull(upliftPct, "pas.renewal.uplift-pct");
    Objects.requireNonNull(rateCapPct, "pas.renewal.rate-cap-pct");
    if (upliftPct.signum() < 0 || rateCapPct.signum() < 0 || maxOpenClaims < 0) {
      throw new IllegalArgumentException("pas.renewal.* values must not be negative");
    }
    stateRateCapPct = stateRateCapPct == null ? Map.of() : Map.copyOf(stateRateCapPct);
  }
}
