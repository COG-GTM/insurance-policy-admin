package com.acme.insurance.pas.policy.lifecycle;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Result of {@code POST /api/v1/policies/{policyNumber}/endorsements}. */
public record EndorsementView(
    String policyNumber,
    int endorsementSeq,
    String endorsementType,
    LocalDate effectiveDate,
    int daysInTerm,
    int daysRemaining,
    BigDecimal proRataFactor,
    BigDecimal premiumAdjustment,
    BigDecimal totalPremium,
    long policyVersion) {}
