package com.acme.insurance.pas.policy.renewal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of a renewal. {@code mode} is {@code APPLIED} when the policy was renewed and {@code
 * SHADOW} when the route is in shadow and the result was only recorded in pas_shadow.
 */
public record RenewalResponse(
    String policyNumber,
    String mode,
    LocalDate priorEffectiveDate,
    LocalDate priorExpiryDate,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    BigDecimal priorPremium,
    BigDecimal totalPremium,
    BigDecimal rateChangePct,
    boolean capped,
    int renewalCount,
    String policyStatus,
    String uwStatus,
    int openClaims) {}
