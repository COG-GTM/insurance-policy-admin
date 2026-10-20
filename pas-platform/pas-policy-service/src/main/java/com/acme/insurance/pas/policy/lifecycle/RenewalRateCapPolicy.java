package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import java.math.BigDecimal;

/**
 * Rate-increase cap applied at renewal. The default is POLRNW's flat cap ({@code
 * pas.renewal.rate-cap-pct}); state-specific caps plug in here once the policy carries a rating
 * state (see {@code pas.renewal.state-rate-cap-pct} and open question OQ-30).
 */
public interface RenewalRateCapPolicy {

  /** Cap in percent for renewing {@code policy}. */
  BigDecimal capPct(PolicyEntity policy);
}
