package com.acme.insurance.pas.batch.premium;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PremiumPolicy(
    String policyNumber,
    String policyType,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit,
    LocalDate effectiveDate,
    LocalDate expiryDate) {}
