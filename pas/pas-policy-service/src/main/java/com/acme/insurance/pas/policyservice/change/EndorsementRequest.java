package com.acme.insurance.pas.policyservice.change;

import java.math.BigDecimal;

/** Body of {@code POST /api/v1/policies/{policyNumber}/endorsements}. */
public record EndorsementRequest(
    String endorsementType, String description, BigDecimal annualPremiumChange) {}
