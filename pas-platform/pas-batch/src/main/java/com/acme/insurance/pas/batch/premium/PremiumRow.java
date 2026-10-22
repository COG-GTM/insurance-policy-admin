package com.acme.insurance.pas.batch.premium;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PremiumRow(
    String policyNumber,
    int coverageSequence,
    LocalDate termEffectiveDate,
    LocalDate termExpiryDate,
    BigDecimal baseRate,
    BigDecimal territoryFactor,
    BigDecimal classFactor,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal discountPct,
    BigDecimal surchargeAmount,
    BigDecimal taxAmount,
    BigDecimal totalPremium,
    String installmentCode,
    BigDecimal installmentAmount,
    LocalDate calculationDate,
    String calculatedBy) {}
