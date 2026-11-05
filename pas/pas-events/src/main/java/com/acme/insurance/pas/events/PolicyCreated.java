package com.acme.insurance.pas.events;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code pas.policy.created} v1. Pseudonymous identifiers only (holder id, agent code); never a
 * name, address or other direct personal data (ADR 0001 D5).
 */
public record PolicyCreated(
    String policyNumber,
    String policyType,
    String policyStatus,
    String uwStatus,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    LocalDate inceptionDate,
    String policyholderId,
    String agentCode,
    String branchCode,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit,
    List<CoverageLine> coverages) {

  public PolicyCreated {
    coverages = List.copyOf(coverages);
  }

  /** One default coverage line. */
  public record CoverageLine(int sequenceNum, String coverageType, String description) {}

  public static PolicyCreated of(Policy p, List<Coverage> coverages) {
    return new PolicyCreated(
        p.policyNumber(),
        p.policyTypeCode(),
        p.policyStatusCode(),
        p.uwStatusCode(),
        p.effectiveDate(),
        p.expiryDate(),
        p.inceptionDate(),
        p.policyholderId(),
        p.agentCode(),
        p.branchCode(),
        p.totalPremium(),
        p.deductible(),
        p.coverageLimit(),
        coverages.stream()
            .map(c -> new CoverageLine(c.sequenceNum(), c.coverageTypeCode(), c.description()))
            .toList());
  }
}
