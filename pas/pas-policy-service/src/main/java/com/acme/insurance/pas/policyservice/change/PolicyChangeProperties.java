package com.acme.insurance.pas.policyservice.change;

import com.acme.insurance.pas.rating.RenewalRules;
import java.math.BigDecimal;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pas.policy-change.*} (AB-526). The defaults reproduce POLRNW: a 5% uplift (Q-05), a flat
 * 15% rate-increase cap (Q-04) and open claims counted but not enforced (Q-02). {@code
 * max-open-claims} enables the FU-02 rule and needs an {@link OpenClaims} bean (FU-17). {@code
 * endorsement-premium-change-enabled} stays off until FU-526-1 is signed off: POLEND adjusts by
 * 0.00 (Q-20), so a non-zero annualPremiumChange is refused.
 */
@ConfigurationProperties("pas.policy-change")
public record PolicyChangeProperties(
    @DefaultValue("5.00") BigDecimal renewalUpliftPct,
    @DefaultValue("15.00") BigDecimal rateIncreaseCapPct,
    @DefaultValue("FEB_28") RenewalRules.LeapDayExpiry leapDayExpiry,
    Integer maxOpenClaims,
    @DefaultValue("UTC") ZoneId businessZone,
    @DefaultValue("false") boolean endorsementPremiumChangeEnabled) {

  public RenewalRules renewalRules() {
    return new RenewalRules(renewalUpliftPct, rateIncreaseCapPct, leapDayExpiry);
  }
}
