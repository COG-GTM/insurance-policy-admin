package com.acme.insurance.pas.underwriting;

import java.time.LocalDate;

/**
 * Claims history through the interface agreed with the Claims team (ADR 0001 D3 item 6, component
 * INT-03, FU-17), never a read of the Claims-owned table. Implementations throw {@link
 * ClaimsHistoryUnavailableException} when the answer cannot be obtained; underwriting then stops
 * without writing anything.
 */
public interface ClaimsHistory {

  /** Claims with a claim date from {@code from} through {@code to}, both inclusive. */
  ClaimsSummary summary(String policyNumber, LocalDate from, LocalDate to);
}
