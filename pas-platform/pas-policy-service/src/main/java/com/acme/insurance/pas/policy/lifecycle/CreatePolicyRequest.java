package com.acme.insurance.pas.policy.lifecycle;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * {@code POST /api/v1/policies} body: the POLNMAP fields POLNEW reads into POLICY-RECORD. Field
 * sizes follow the copybook (400 when exceeded); POLNEW's own checks (blank holder/type, past
 * effective date, zero limit, unknown holder) are applied by {@link NewBusinessRules} and return
 * 422 with the COBOL message.
 */
public record CreatePolicyRequest(
    @Schema(example = "CUST000001", maxLength = 10) @Size(max = 10) String policyholderId,
    @Schema(example = "AUT", description = "AUT, HOM, COM, LIF or HLT", maxLength = 3)
        @Size(max = 3)
        String policyType,
    @Schema(example = "2026-11-01") @NotNull @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate effectiveDate,
    @Schema(example = "2027-11-01") @NotNull @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate expiryDate,
    @Schema(example = "100000") @NotNull @Digits(integer = 11, fraction = 2)
        BigDecimal coverageLimit,
    @Schema(example = "1200") @Digits(integer = 9, fraction = 2) BigDecimal totalPremium,
    @Schema(example = "500") @Digits(integer = 7, fraction = 2) BigDecimal deductible,
    @Schema(example = "AG0001", maxLength = 6) @Size(max = 6) String agentCode,
    @Schema(example = "B001", maxLength = 4) @Size(max = 4) String branchCode) {

  /** Same request with amounts at the copybook scale, for idempotency hashing. */
  CreatePolicyRequest normalised() {
    return new CreatePolicyRequest(
        policyholderId,
        policyType,
        effectiveDate,
        expiryDate,
        cents(coverageLimit),
        cents(totalPremium),
        cents(deductible),
        agentCode,
        branchCode);
  }

  static BigDecimal cents(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY);
  }
}
