package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The priced renewal term (POLRNW 4000 and 5000).
 *
 * @param previousPremium the expiring term's TOTAL_PREMIUM
 * @param newPremium the renewal premium, truncated to the cent (Q-06)
 * @param rateChangePct the uncapped rate change in percent, truncated to 2 places, as POLRNW keeps
 *     it in WS-RATE-CHANGE-PCT; 0.00 for a zero premium (Q-41)
 * @param capApplied whether the rate-increase cap reduced the premium
 * @param effectiveDate new EFFECTIVE_DATE: the old expiry
 * @param expiryDate new EXPIRY_DATE: one year later, a valid date (Q-01)
 * @param renewalCount new RENEWAL_COUNT
 */
public record RenewalQuote(
    BigDecimal previousPremium,
    BigDecimal newPremium,
    BigDecimal rateChangePct,
    boolean capApplied,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    int renewalCount) {}
