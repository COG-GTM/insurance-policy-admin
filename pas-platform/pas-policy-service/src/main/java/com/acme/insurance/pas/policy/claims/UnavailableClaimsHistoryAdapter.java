package com.acme.insurance.pas.policy.claims;

import java.time.LocalDate;

/**
 * Default adapter until the Claims-owned interface exists (Pending: Claims team). Every call fails
 * with {@link ClaimsHistoryUnavailableException} so underwriting is refused rather than scored on
 * an assumed clean history.
 */
public class UnavailableClaimsHistoryAdapter implements ClaimsHistoryPort {

  @Override
  public ClaimsSummary summarize(String policyNumber, LocalDate asOf) {
    throw new ClaimsHistoryUnavailableException(
        "Claims history interface is not configured (pas.claims.adapter); pending the Claims team"
            + " API");
  }
}
