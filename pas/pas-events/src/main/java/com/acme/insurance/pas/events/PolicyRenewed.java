package com.acme.insurance.pas.events;

import com.acme.insurance.pas.domain.Policy;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code pas.policy.renewed} v1 (AB-528): the policy after a renewal (POLRNW). Pseudonymous
 * identifiers only (ADR 0001 D5).
 */
public record PolicyRenewed(
    String policyNumber,
    String policyType,
    String policyStatus,
    String uwStatus,
    LocalDate previousEffectiveDate,
    LocalDate previousExpiryDate,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    Integer renewalCount,
    BigDecimal previousPremium,
    BigDecimal totalPremium,
    String policyholderId,
    String agentCode,
    String branchCode) {

  public static PolicyRenewed of(Policy before, Policy after) {
    return new PolicyRenewed(
        after.policyNumber(),
        after.policyTypeCode(),
        after.policyStatusCode(),
        after.uwStatusCode(),
        before.effectiveDate(),
        before.expiryDate(),
        after.effectiveDate(),
        after.expiryDate(),
        after.renewalCount(),
        before.totalPremium(),
        after.totalPremium(),
        after.policyholderId(),
        after.agentCode(),
        after.branchCode());
  }
}
