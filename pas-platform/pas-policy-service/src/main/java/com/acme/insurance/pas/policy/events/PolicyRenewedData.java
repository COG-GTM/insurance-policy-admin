package com.acme.insurance.pas.policy.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code PolicyRenewed} payload (schema {@code events/schemas/v1/PolicyRenewed.schema.json}). The
 * renewed term as committed by the POLRNW replacement (AB-526); no policyholder PII.
 */
public record PolicyRenewedData(
    String policyType,
    String policyStatus,
    String uwStatus,
    LocalDate previousExpiryDate,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    int renewalCount,
    BigDecimal previousPremium,
    BigDecimal totalPremium,
    BigDecimal rateChangePct) {}
