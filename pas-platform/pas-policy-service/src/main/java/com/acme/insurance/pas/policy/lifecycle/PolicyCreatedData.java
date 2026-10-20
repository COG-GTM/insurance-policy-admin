package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.persistence.CoverageEntity;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code PolicyCreated} event data (replaces POLNEW's TDQ write of POLICY-RECORD to {@code
 * ACME.PAS.UNDERWRITING.REQUEST}). Identifiers and business facts only; no policyholder PII.
 */
public record PolicyCreatedData(
    String policyType,
    String policyStatus,
    String uwStatus,
    String policyholderId,
    String agentCode,
    String branchCode,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    LocalDate inceptionDate,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal totalPremium,
    List<CoverageSummary> coverages) {

  public PolicyCreatedData {
    coverages = List.copyOf(coverages);
  }

  /** One default coverage. */
  public record CoverageSummary(int sequenceNum, String coverageType) {}

  static PolicyCreatedData of(PolicyEntity p, List<CoverageEntity> coverages) {
    return new PolicyCreatedData(
        trim(p.getPolicyType()),
        trim(p.getPolicyStatus()),
        trim(p.getUwStatus()),
        trim(p.getPolicyholderId()),
        trim(p.getAgentCode()),
        trim(p.getBranchCode()),
        p.getEffectiveDate(),
        p.getExpiryDate(),
        p.getInceptionDate(),
        p.getCoverageLimit(),
        p.getDeductible(),
        p.getTotalPremium(),
        coverages.stream()
            .map(c -> new CoverageSummary(c.getId().getSequenceNum(), trim(c.getCoverageType())))
            .toList());
  }

  private static String trim(String value) {
    return value == null ? null : value.strip();
  }
}
