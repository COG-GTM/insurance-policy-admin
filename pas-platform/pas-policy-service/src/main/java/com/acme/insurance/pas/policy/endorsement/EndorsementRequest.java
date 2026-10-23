package com.acme.insurance.pas.policy.endorsement;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * POLEND input. {@code type} is required (Q-12); the type-specific block it needs is validated by
 * the service: CAD {@code coverage}, CRM {@code coverageSeq}, LCH {@code coverageSeq} + {@code
 * newLimit}, ACH {@code address}, CAN none.
 */
public record EndorsementRequest(
    @Schema(description = "CAD, CRM, LCH, ACH or CAN", example = "LCH") String type,
    @Schema(description = "Endorsement effective date; defaults to today", example = "2024-07-01")
        LocalDate effectiveDate,
    @Size(max = 100) String description,
    Integer coverageSeq,
    @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 11, fraction = 2)
        BigDecimal newLimit,
    @Valid CoverageAddition coverage,
    @Valid AddressChange address) {

  /** CAD: the coverage to add. */
  public record CoverageAddition(
      @NotBlank @Size(max = 4) String coverageType,
      @Size(max = 40) String description,
      @DecimalMin("0.00") @Digits(integer = 11, fraction = 2) BigDecimal coverageLimit,
      @DecimalMin("0.00") @Digits(integer = 7, fraction = 2) BigDecimal deductible,
      @DecimalMin("0.00") @Digits(integer = 9, fraction = 2) BigDecimal premium) {}

  /** ACH: the new policyholder mailing address. */
  public record AddressChange(
      @NotBlank @Size(max = 40) String addrLine1,
      @Size(max = 40) String addrLine2,
      @NotBlank @Size(max = 25) String city,
      @NotBlank @Size(min = 2, max = 2) String stateCode,
      @NotBlank @Size(max = 10) String zipCode) {}
}
