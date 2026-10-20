package com.acme.insurance.pas.policy.lifecycle;

import java.math.BigDecimal;
import java.time.LocalDate;

/** {@code data} of the {@code PolicyRenewed} event (no PII). */
public record PolicyRenewedData(
    BigDecimal previousPremium,
    BigDecimal newPremium,
    BigDecimal rateChangePct,
    boolean rateCapApplied,
    LocalDate previousEffectiveDate,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    int renewalCount,
    String policyStatus,
    String uwStatus,
    String agentCode,
    String branchCode) {}
