package com.acme.insurance.pas.events;

import com.acme.insurance.pas.domain.Policy;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code pas.underwriting.requested} v1: the underwriting referral POLNEW sent as the whole
 * POLICY-RECORD on the TD queue. Carries the policy fields underwriting needs; the 125-byte record
 * for MQ consumers is the bridge's job (AB-528).
 */
public record UnderwritingRequested(
    String policyNumber,
    String policyType,
    String policyholderId,
    String agentCode,
    String branchCode,
    LocalDate effectiveDate,
    BigDecimal totalPremium,
    BigDecimal coverageLimit,
    String uwStatus) {

  public static UnderwritingRequested of(Policy p) {
    return new UnderwritingRequested(
        p.policyNumber(),
        p.policyTypeCode(),
        p.policyholderId(),
        p.agentCode(),
        p.branchCode(),
        p.effectiveDate(),
        p.totalPremium(),
        p.coverageLimit(),
        p.uwStatusCode());
  }
}
