package com.acme.insurance.pas.policy.underwriting;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Manual override body; the overriding user is the caller's JWT subject. */
public record OverrideRequest(
    @Schema(
            example = "AP",
            allowableValues = {"AP", "RS", "RM", "DC"})
        @NotNull
        @Pattern(regexp = "AP|RS|RM|DC")
        String decisionCode,
    @Schema(example = "Loss history reviewed; prior claims were subrogated", maxLength = 200)
        @NotBlank
        @Size(max = 200)
        String reason) {}
