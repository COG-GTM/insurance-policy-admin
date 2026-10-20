package com.acme.insurance.pas.policy.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code PolicyEndorsed} payload (schema {@code events/schemas/v1/PolicyEndorsed.schema.json}). One
 * committed POLEND endorsement (AB-526); replaces the ENDORSEMENT-AUDIT TS queue record.
 */
public record PolicyEndorsedData(
    int endorsementSeq,
    String endorsementType,
    LocalDate endorsementEffectiveDate,
    BigDecimal premiumAdjustment,
    BigDecimal totalPremium,
    String policyStatus,
    LocalDate effectiveDate,
    LocalDate expiryDate) {}
