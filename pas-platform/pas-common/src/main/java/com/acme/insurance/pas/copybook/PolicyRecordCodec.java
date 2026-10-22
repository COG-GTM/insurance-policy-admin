package com.acme.insurance.pas.copybook;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Maps POLICY-RECORD to its table-shaped domain type. Date PICs become UTC midnight (Q-28).
 * RISK-SCORE and RENEWAL-COUNT use legacy truncation/modulo semantics (Q-20, Q-18); other
 * conversions reject overflow. Nullable numerics become zero in copybook output (Q-26).
 */
public final class PolicyRecordCodec {
  private final CopybookCodec codec;

  public PolicyRecordCodec(CopybookCharset charset) {
    codec = new CopybookCodec(PasCopybooks.POLICY_RECORD, charset);
  }

  public Policy decode(byte[] bytes) {
    CopybookCodec.RecordReader reader = codec.reader(bytes);
    LocalDate updated = reader.date("POLICY-LAST-UPDATED");
    return new Policy(
        reader.string("POLICY-NUMBER"),
        Coded.of(PolicyType.class, reader.string("POLICY-TYPE")),
        Coded.of(PolicyStatus.class, reader.string("POLICY-STATUS")),
        reader.date("POLICY-EFFECTIVE-DATE"),
        reader.date("POLICY-EXPIRY-DATE"),
        reader.string("POLICY-HOLDER-ID"),
        reader.string("POLICY-AGENT-CODE"),
        reader.string("POLICY-BRANCH-CODE"),
        reader.decimal("POLICY-TOTAL-PREMIUM"),
        reader.decimal("POLICY-DEDUCTIBLE"),
        reader.decimal("POLICY-LIMIT"),
        reader.date("POLICY-INCEPTION-DATE"),
        reader.integer("POLICY-RENEWAL-COUNT"),
        Coded.of(UnderwritingStatus.class, reader.string("POLICY-UW-STATUS")),
        reader.integer("POLICY-RISK-SCORE"),
        Coded.of(YesNo.class, reader.string("POLICY-WEB-IND")),
        Coded.of(YesNo.class, reader.string("POLICY-API-FLAG")),
        atUtc(updated),
        reader.string("POLICY-UPDATED-BY"));
  }

  public byte[] encode(Policy policy) {
    return codec
        .writer()
        .putString("POLICY-NUMBER", policy.policyNumber())
        .putString("POLICY-TYPE", raw(policy.policyType()))
        .putString("POLICY-STATUS", raw(policy.policyStatus()))
        .putDate("POLICY-EFFECTIVE-DATE", policy.effectiveDate())
        .putDate("POLICY-EXPIRY-DATE", policy.expiryDate())
        .putString("POLICY-HOLDER-ID", policy.policyholderId())
        .putString("POLICY-AGENT-CODE", policy.agentCode())
        .putString("POLICY-BRANCH-CODE", policy.branchCode())
        .putDecimal("POLICY-TOTAL-PREMIUM", zero(policy.totalPremium()))
        .putDecimal("POLICY-DEDUCTIBLE", zero(policy.deductible()))
        .putDecimal("POLICY-LIMIT", zero(policy.coverageLimit()))
        .putDate("POLICY-INCEPTION-DATE", policy.inceptionDate())
        .putDecimal(
            "POLICY-RENEWAL-COUNT",
            policy.renewalCount() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(policy.renewalCount()),
            OverflowPolicy.TRUNCATE)
        .putString("POLICY-UW-STATUS", raw(policy.uwStatus()))
        .putDecimal(
            "POLICY-RISK-SCORE",
            policy.riskScore() == null ? BigDecimal.ZERO : BigDecimal.valueOf(policy.riskScore()),
            OverflowPolicy.TRUNCATE)
        .putString("POLICY-WEB-IND", raw(policy.webIndicator()))
        .putString("POLICY-API-FLAG", raw(policy.apiFlag()))
        .putDate(
            "POLICY-LAST-UPDATED",
            policy.lastUpdated() == null ? null : policy.lastUpdated().toLocalDate())
        .putString("POLICY-UPDATED-BY", policy.updatedBy())
        .toByteArray();
  }

  private static String raw(Coded<?> coded) {
    return coded == null ? null : coded.raw();
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static OffsetDateTime atUtc(LocalDate date) {
    return date == null ? null : date.atStartOfDay().atOffset(ZoneOffset.UTC);
  }
}
