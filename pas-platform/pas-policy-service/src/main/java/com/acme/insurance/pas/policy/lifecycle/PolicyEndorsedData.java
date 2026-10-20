package com.acme.insurance.pas.policy.lifecycle;

import java.math.BigDecimal;
import java.time.LocalDate;

/** {@code data} of the {@code PolicyEndorsed} event (no PII). */
public record PolicyEndorsedData(
    int endorsementSeq,
    String endorsementType,
    LocalDate effectiveDate,
    int daysInTerm,
    int daysRemaining,
    BigDecimal proRataFactor,
    BigDecimal premiumAdjustment,
    BigDecimal totalPremium,
    String agentCode,
    String branchCode) {}
