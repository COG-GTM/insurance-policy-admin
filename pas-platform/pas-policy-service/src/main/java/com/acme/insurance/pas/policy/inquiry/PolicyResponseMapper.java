package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Policy;
import java.time.LocalDate;

public final class PolicyResponseMapper {
  private PolicyResponseMapper() {}

  public static PolicyResponse map(Policy policy) {
    return new PolicyResponse(
        policy.policyNumber(),
        raw(policy.policyType()),
        raw(policy.policyStatus()),
        date(policy.effectiveDate()),
        date(policy.expiryDate()),
        policy.policyholderId(),
        policy.agentCode(),
        policy.branchCode(),
        policy.totalPremium(),
        policy.deductible(),
        policy.coverageLimit(),
        date(policy.inceptionDate()),
        policy.renewalCount() == null ? 0 : policy.renewalCount(),
        raw(policy.uwStatus()),
        policy.riskScore() == null ? 0 : policy.riskScore(),
        raw(policy.webIndicator()),
        raw(policy.apiFlag()),
        policy.lastUpdated().toInstant().toEpochMilli(),
        policy.updatedBy());
  }

  private static String date(LocalDate date) {
    return date == null ? null : date.toString();
  }

  private static String raw(Coded<?> value) {
    return value == null ? null : value.raw();
  }
}
