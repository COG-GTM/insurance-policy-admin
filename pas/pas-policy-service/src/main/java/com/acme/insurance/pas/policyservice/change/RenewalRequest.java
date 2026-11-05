package com.acme.insurance.pas.policyservice.change;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/v1/policies/{policyNumber}/renewals}; optional.
 *
 * @param expectedExpiryDate renew only while the policy still expires on this date (409 if not)
 */
public record RenewalRequest(LocalDate expectedExpiryDate) {}
