package com.acme.insurance.pas.policy.claims;

import java.time.LocalDate;

/**
 * Read-only claims history from the Claims-owned system (ACMEINS.CLAIMS is not PAS data). PAS never
 * reads the claims table directly; adapters implement the interface agreed with the Claims team
 * (docs/modernization/claims-interface-assumptions.md, agreement Pending: Claims team).
 *
 * <p>Contract (pinned by {@code ClaimsHistoryPortContract}):
 *
 * <ul>
 *   <li>{@code openClaimCount}: claims for the policy whose status is open, any date (POLRNW).
 *   <li>{@code claimCount5y} / {@code incurredAmount5y}: claims dated on or after {@code asOf}
 *       minus five calendar years, boundary day included, no upper bound (UNDWRT {@code CLAIM_DATE
 *       >= CURRENT DATE - 5 YEARS}); the amount is the sum of incurred, 0.00 when none.
 *   <li>An unknown policy has no claims (all zero), it is not an error.
 *   <li>When the source cannot answer, throw {@link ClaimsHistoryUnavailableException}; never
 *       report "no claims" instead.
 * </ul>
 */
public interface ClaimsHistoryPort {

  ClaimsSummary summarize(String policyNumber, LocalDate asOf);
}
