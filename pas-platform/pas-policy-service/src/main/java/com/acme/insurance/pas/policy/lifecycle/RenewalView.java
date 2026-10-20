package com.acme.insurance.pas.policy.lifecycle;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Result of {@code POST /api/v1/policies/{policyNumber}/renewals}. */
public record RenewalView(
    String policyNumber,
    BigDecimal previousPremium,
    BigDecimal newPremium,
    BigDecimal rateChangePct,
    BigDecimal rateCapPct,
    boolean rateCapApplied,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    int renewalCount,
    String policyStatus,
    String uwStatus,
    int coveragesRedated,
    long policyVersion) {}
