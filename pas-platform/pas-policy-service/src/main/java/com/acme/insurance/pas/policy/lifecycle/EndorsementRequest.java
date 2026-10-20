package com.acme.insurance.pas.policy.lifecycle;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /api/v1/policies/{policyNumber}/endorsements}. POLEND never received the type
 * from its map (quirk register Q-10), so the API takes it as input; a missing or blank type is
 * rejected with POLEND's message. POLEND never wrote a description or a requested effective date
 * (Q-15), so neither is accepted.
 */
public record EndorsementRequest(
    @Schema(
            description = "Endorsement type: CAD, CRM, LCH, ACH or CAN (POLEND 88-levels)",
            example = "ACH",
            maxLength = 3)
        String endorsementType) {}
