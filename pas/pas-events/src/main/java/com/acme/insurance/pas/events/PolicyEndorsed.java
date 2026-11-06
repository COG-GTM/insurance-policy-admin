package com.acme.insurance.pas.events;

import com.acme.insurance.pas.domain.Policy;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code pas.policy.endorsed} v1 (AB-528): one endorsement (POLEND). The free-text description
 * stays in ENDORSEMENTS and the audit table; events never carry free text (ADR 0001 D5).
 */
public record PolicyEndorsed(
    String policyNumber,
    String policyType,
    String policyStatus,
    int endorsementSeq,
    String endorsementType,
    LocalDate endorsementDate,
    BigDecimal premiumAdjustment,
    BigDecimal totalPremium,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String policyholderId,
    String agentCode,
    String branchCode) {

  public static PolicyEndorsed of(
      Policy policy,
      int endorsementSeq,
      String endorsementType,
      LocalDate endorsementDate,
      BigDecimal premiumAdjustment,
      BigDecimal totalPremium) {
    return new PolicyEndorsed(
        policy.policyNumber(),
        policy.policyTypeCode(),
        policy.policyStatusCode(),
        endorsementSeq,
        endorsementType,
        endorsementDate,
        premiumAdjustment,
        totalPremium,
        policy.effectiveDate(),
        policy.expiryDate(),
        policy.policyholderId(),
        policy.agentCode(),
        policy.branchCode());
  }
}
