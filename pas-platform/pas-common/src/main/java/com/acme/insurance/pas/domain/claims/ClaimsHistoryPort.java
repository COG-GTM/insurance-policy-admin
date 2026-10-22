package com.acme.insurance.pas.domain.claims;

import java.time.LocalDate;

/**
 * Claims history belongs to the Claims team; ACMEINS.CLAIMS is not migrated (M-01). Adapters arrive
 * with AB-525/526; see docs/modernization/claims-table-assumptions.md.
 */
public interface ClaimsHistoryPort {
  int countOpenClaims(String policyNumber);

  ClaimsSummary summarizeSince(String policyNumber, LocalDate since);
}
