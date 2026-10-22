package com.acme.insurance.pas.policy.endorsement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of an endorsement. {@code proRataFactor} is reported but not applied (Q-11), so {@code
 * premiumAdjustment} is 0.00. {@code mode} is APPLIED or SHADOW.
 */
public record EndorsementResponse(
    String policyNumber,
    String mode,
    int endorsementSeq,
    String type,
    LocalDate effectiveDate,
    String description,
    BigDecimal proRataFactor,
    BigDecimal premiumAdjustment,
    BigDecimal totalPremium,
    String policyStatus) {}
