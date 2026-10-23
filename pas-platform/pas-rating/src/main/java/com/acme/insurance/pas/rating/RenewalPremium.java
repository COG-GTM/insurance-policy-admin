package com.acme.insurance.pas.rating;

import java.math.BigDecimal;

/**
 * POLRNW 4000-CALCULATE-NEW-PREMIUM result.
 *
 * @param oldPremium premium of the expiring term
 * @param newPremium premium of the renewal term after the uplift and the cap
 * @param rateChangePct uplift as a percentage of the old premium, truncated to S9(3)V99
 * @param capped whether the rate-increase cap (Q-04) replaced the uplifted premium
 */
public record RenewalPremium(
    BigDecimal oldPremium, BigDecimal newPremium, BigDecimal rateChangePct, boolean capped) {}
