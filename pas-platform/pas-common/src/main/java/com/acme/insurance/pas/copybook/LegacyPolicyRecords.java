package com.acme.insurance.pas.copybook;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.UnderwritingStatus;

public final class LegacyPolicyRecords {
  private LegacyPolicyRecords() {}

  public static byte[] polnewReferralRecord(Policy policy, CopybookCharset charset) {
    return new PolicyRecordCodec(charset).encode(policy);
  }

  public static byte[] undwrtDecisionRecord(Policy policy, CopybookCharset charset) {
    Policy decision =
        new Policy(
            policy.policyNumber(),
            policy.policyType(),
            policy.policyStatus(),
            policy.effectiveDate(),
            policy.expiryDate(),
            policy.policyholderId(),
            policy.agentCode(),
            policy.branchCode(),
            policy.totalPremium(),
            policy.deductible(),
            policy.coverageLimit(),
            policy.inceptionDate(),
            policy.renewalCount(),
            Coded.of(UnderwritingStatus.class, raw(policy.uwStatus())),
            policy.riskScore(),
            policy.webIndicator(),
            policy.apiFlag(),
            policy.lastUpdated(),
            policy.updatedBy());
    return new PolicyRecordCodec(charset).encode(decision);
  }

  private static String raw(Coded<?> value) {
    return value == null ? null : value.raw();
  }
}
