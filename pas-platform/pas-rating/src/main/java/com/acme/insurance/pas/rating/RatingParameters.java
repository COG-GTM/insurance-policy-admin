package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RatingParameters(
    String policyTypeKey,
    LocalDate effectiveDate,
    BigDecimal basePremium,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal taxRate,
    BigDecimal surchargeAmount) {}
