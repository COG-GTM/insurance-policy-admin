package com.acme.insurance.pas.policy.renewal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * @param termExpiryDate expiry date of the term being renewed; the per-term idempotency key (Q-17)
 */
public record RenewalRequest(
    @Schema(
            description =
                "Expiry date of the term being renewed. A second request for the same term is 409.",
            example = "2025-01-01")
        @NotNull
        LocalDate termExpiryDate) {}
